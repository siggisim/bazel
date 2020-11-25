// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.worker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.shell.Subprocess;
import com.google.devtools.build.lib.shell.SubprocessBuilder;
import com.google.devtools.build.lib.shell.SubprocessFactory;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * An intermediate worker that sends requests and receives responses from the worker processes.
 * There is at most one of these per {@code WorkerKey}, corresponding to one worker process. {@code
 * WorkerMultiplexer} objects run in separate long-lived threads. {@code WorkerProxy} objects call
 * into them to send requests. When a worker process returns a {@code WorkResponse}, {@code
 * WorkerMultiplexer} wakes up the relevant {@code WorkerProxy} to retrieve the response.
 */
public class WorkerMultiplexer extends Thread {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  /**
   * A map of {@code WorkResponse}s received from the worker process. They are stored in this map
   * keyed by the request id until the corresponding {@code WorkerProxy} picks them up.
   */
  private final ConcurrentMap<Integer, WorkResponse> workerProcessResponse;
  /**
   * A map of semaphores corresponding to {@code WorkRequest}s. After sending the {@code
   * WorkRequest}, {@code WorkerProxy} will wait on a semaphore to be released. {@code
   * WorkerMultiplexer} is responsible for releasing the corresponding semaphore in order to signal
   * {@code WorkerProxy} that the {@code WorkerResponse} has been received.
   */
  private final ConcurrentMap<Integer, Semaphore> responseChecker;
  /** The worker process that this WorkerMultiplexer should be talking to. */
  private Subprocess process;
  /**
   * Set to true if one of the worker processes returns an unparseable response, or for other
   * reasons we can't properly handle the remaining responses. We then discard all the responses
   * from other work requests and abort.
   */
  private boolean isWorkerStreamCorrupted;
  /** InputStream from the worker process. */
  private RecordingInputStream recordingStream;
  /**
   * True if we have received EOF on the stream from the worker process. We then stop processing,
   * and all workers still waiting for responses will fail.
   */
  private boolean isWorkerStreamClosed;
  /** True if this multiplexer was explicitly destroyed. */
  private boolean wasDestroyed;
  /**
   * The log file of the actual running worker process. It is shared between all WorkerProxy
   * instances for this multiplexer.
   */
  private final Path logFile;

  /** The worker key that this multiplexer is for. */
  private final WorkerKey workerKey;

  /** For testing only, allow a way to fake subprocesses. */
  private SubprocessFactory subprocessFactory;

  /**
   * The active Reporter object, non-null if {@code --worker_verbose} is set. This must be cleared
   * at the end of a command execution.
   */
  public EventHandler reporter;

  WorkerMultiplexer(Path logFile, WorkerKey workerKey) {
    this.logFile = logFile;
    this.workerKey = workerKey;
    responseChecker = new ConcurrentHashMap<>();
    workerProcessResponse = new ConcurrentHashMap<>();
    isWorkerStreamCorrupted = false;
    isWorkerStreamClosed = false;
    wasDestroyed = false;
  }

  /** Sets or clears the reporter for outputting verbose info. */
  void setReporter(EventHandler reporter) {
    this.reporter = reporter;
  }

  /** Reports a string to the user if reporting is enabled. */
  private void report(String s) {
    EventHandler r = this.reporter; // Protect against race condition with setReporter().
    if (r != null && s != null) {
      r.handle(Event.info(s));
    }
  }

  /**
   * Creates a worker process corresponding to this {@code WorkerMultiplexer}, if it doesn't already
   * exist. Also makes sure this {@code WorkerMultiplexer} runs as a separate thread.
   */
  public synchronized void createProcess(Path workDir) throws IOException {
    // The process may have died in the meanwhile (e.g. between builds).
    if (this.process == null || !this.process.isAlive()) {
      ImmutableList<String> args = workerKey.getArgs();
      File executable = new File(args.get(0));
      if (!executable.isAbsolute() && executable.getParent() != null) {
        List<String> newArgs = new ArrayList<>(args);
        newArgs.set(0, new File(workDir.getPathFile(), newArgs.get(0)).getAbsolutePath());
        args = ImmutableList.copyOf(newArgs);
      }
      isWorkerStreamCorrupted = false;
      isWorkerStreamClosed = false;
      SubprocessBuilder processBuilder =
          subprocessFactory != null
              ? new SubprocessBuilder(subprocessFactory)
              : new SubprocessBuilder();
      processBuilder.setArgv(args);
      processBuilder.setWorkingDirectory(workDir.getPathFile());
      processBuilder.setStderr(logFile.getPathFile());
      processBuilder.setEnv(workerKey.getEnv());
      this.process = processBuilder.start();
    }
    if (!this.isAlive()) {
      this.start();
    }
  }

  /**
   * Returns the path of the log file shared by all multiplex workers using this process. May be
   * null if the process has not started yet.
   */
  public Path getLogFile() {
    return logFile;
  }

  /**
   * Signals this object to destroy itself, including the worker process. The object might not be
   * fully destroyed at the end of this call, but will terminate soon.
   */
  public synchronized void destroyMultiplexer() {
    if (this.process != null) {
      destroyProcess(this.process);
      this.process = null;
    }
    wasDestroyed = true;
  }

  /** Destroys the worker subprocess. This might block forever if the subprocess refuses to die. */
  private void destroyProcess(Subprocess process) {
    boolean wasInterrupted = false;
    try {
      process.destroy();
      while (true) {
        try {
          process.waitFor();
          return;
        } catch (InterruptedException ie) {
          wasInterrupted = true;
        }
      }
    } finally {
      // Read this for detailed explanation: http://www.ibm.com/developerworks/library/j-jtp05236/
      if (wasInterrupted) {
        Thread.currentThread().interrupt(); // preserve interrupted status
      }
      isWorkerStreamClosed = true;
    }
  }

  /**
   * Sends the WorkRequest to worker process. This method is called on the thread of a {@code
   * WorkerProxy}, and so is subject to interrupts by dynamic execution.
   */
  public synchronized void putRequest(WorkRequest request) throws IOException {
    responseChecker.put(request.getRequestId(), new Semaphore(0));
    try {
      request.writeDelimitedTo(process.getOutputStream());
      process.getOutputStream().flush();
    } catch (IOException e) {
      // We can't know how much of the request was sent, so we have to assume the worker's input
      // now contains garbage.
      // TODO(b/151767359): Avoid causing garbage! Maybe by sending in a separate thread?
      isWorkerStreamCorrupted = true;
      if (e instanceof InterruptedIOException) {
        Thread.currentThread().interrupt();
      }
      responseChecker.remove(request.getRequestId());
      throw e;
    }
  }

  /**
   * Waits on a semaphore for the {@code WorkResponse} returned from worker process. This method is
   * called on the thread of a {@code WorkerProxy}, and so is subject to interrupts by dynamic
   * execution.
   */
  public WorkResponse getResponse(Integer requestId) throws InterruptedException {
    try {
      Semaphore waitForResponse = responseChecker.get(requestId);

      if (waitForResponse == null) {
        report("Null response semaphore for " + requestId);
        // If the multiplexer is interrupted when a {@code WorkerProxy} is trying to send a request,
        // the request is not sent, so there is no need to wait for a response.
        return null;
      }

      // Wait for the multiplexer to get our response and release this semaphore. The semaphore will
      // throw {@code InterruptedException} when the multiplexer is terminated.
      waitForResponse.acquire();
      report("Acquired response semaphore for " + requestId);

      WorkResponse workResponse = workerProcessResponse.get(requestId);
      report("Response for " + requestId + " is " + workResponse);
      return workResponse;
    } finally {
      responseChecker.remove(requestId);
      workerProcessResponse.remove(requestId);
    }
  }

  /**
   * Waits to read a {@code WorkResponse} from worker process, put that {@code WorkResponse} in
   * {@code workerProcessResponse} and release the semaphore for the {@code WorkerProxy}.
   *
   * <p>This is only called on the WorkerMultiplexer thread and so cannot be interrupted by dynamic
   * execution cancellation.
   */
  private void waitResponse() throws InterruptedException, IOException {
    Subprocess p = this.process;
    if (p == null || !p.isAlive()) {
      // Avoid busy-wait for a new process.
      Thread.sleep(1);
      return;
    }
    recordingStream = new RecordingInputStream(p.getInputStream());
    recordingStream.startRecording(4096);
    WorkResponse parsedResponse = WorkResponse.parseDelimitedFrom(recordingStream);

    // A null parsedResponse can only happen if the input stream is closed, in which case we
    // drop everything.
    if (parsedResponse == null) {
      isWorkerStreamClosed = true;
      report(
          String.format(
              "Multiplexer process for %s has closed its output, aborting multiplexer",
              workerKey.getMnemonic()));
      return;
    }

    int requestId = parsedResponse.getRequestId();

    workerProcessResponse.put(requestId, parsedResponse);

    // TODO(b/151767359): When allowing cancellation, just remove responses that have no matching
    // entry in responseChecker.
    Semaphore semaphore = responseChecker.get(requestId);
    if (semaphore != null) {
      // This wakes up the WorkerProxy that should receive this response.
      semaphore.release();
    } else {
      report(String.format("Multiplexer for %s found no semaphore", workerKey.getMnemonic()));
      workerProcessResponse.remove(requestId);
    }
  }

  /** The multiplexer thread that listens to the WorkResponse from worker process. */
  @Override
  public void run() {
    while (!isWorkerStreamClosed && !isWorkerStreamCorrupted) {
      try {
        waitResponse();
      } catch (IOException e) {
        // We got this exception while reading from the worker's stdout. We can't trust the
        // output any more at that point.
        isWorkerStreamCorrupted = true;
        if (e instanceof InterruptedIOException) {
          report(
              String.format(
                  "Multiplexer process for %s was interrupted during I/O, aborting multiplexer",
                  workerKey.getMnemonic()));
        } else {
          // TODO(larsrc): Output the recorded message.
          report(
              String.format(
                  "Multiplexer for %s got IOException reading a response, aborting multiplexer",
                  workerKey.getMnemonic()));
          logger.atWarning().withCause(e).log(
              "Caught IOException while waiting for worker response. "
                  + "It could be because the worker returned an unparseable response.");
        }
      } catch (InterruptedException e) {
        // This should only happen when the Blaze build has been aborted (failed or cancelled). In
        // that case, there may still be some outstanding requests in the worker process, which we
        // will let fall on the floor, but we still want to leave the process running for the next
        // build.
        // TODO(b/151767359): Cancel all outstanding requests when cancellation is implemented.
        releaseAllSemaphores();
      }
    }
    // If we get here, the worker process is either dead or corrupted. We could attempt to restart
    // it, but the outstanding requests will have failed already. Until we have a way to signal
    // transient failures, we have to just reject all further requests and make sure the process
    // is really dead
    synchronized (this) {
      if (process != null && process.isAlive()) {
        destroyMultiplexer();
      }
      releaseAllSemaphores();
    }
  }

  /**
   * Release all the semaphores and clear the related maps. Must only be called when we are shutting
   * down the multiplexer.
   */
  private void releaseAllSemaphores() {
    for (Semaphore semaphore : responseChecker.values()) {
      semaphore.release();
    }
    responseChecker.clear();
    workerProcessResponse.clear();
  }

  String getRecordingStreamMessage() {
    // Unlike SingleplexWorker, we don't want to read the remaining bytes, as those could contain
    // many other responses. We just return what we actually read.
    return recordingStream.getRecordedDataAsString();
  }

  /** Returns true if this process has died for other reasons than a call to {@code #destroy()}. */
  boolean diedUnexpectedly() {
    Subprocess p = this.process; // Protects against this.process getting null.
    return p != null && !p.isAlive() && !wasDestroyed;
  }

  /** Returns the exit value of multiplexer's process, if it has exited. */
  Optional<Integer> getExitValue() {
    Subprocess p = this.process; // Protects against this.process getting null.
    return p != null && !p.isAlive() ? Optional.of(p.exitValue()) : Optional.empty();
  }

  /** For testing only, to verify that maps are cleared after responses are reaped. */
  @VisibleForTesting
  boolean noOutstandingRequests() {
    return responseChecker.isEmpty() && workerProcessResponse.isEmpty();
  }

  @VisibleForTesting
  void setProcessFactory(SubprocessFactory factory) {
    subprocessFactory = factory;
  }
}
