// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.windows;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.testutil.TestSpec;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.windows.util.WindowsTestUtil;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link WindowsFileOperations}. */
@RunWith(JUnit4.class)
@TestSpec(localOnly = true, supportedOs = OS.WINDOWS)
public class WindowsFileOperationsTest {

  private String scratchRoot;

  @Before
  public void loadJni() throws Exception {
    String jniDllPath = WindowsTestUtil.getRunfile("io_bazel/src/main/native/windows_jni.dll");
    WindowsJniLoader.loadJniForTesting(jniDllPath);
    scratchRoot = new File(System.getenv("TEST_TMPDIR")).getAbsolutePath() + "/x";
    deleteAllUnder(scratchRoot);
  }

  @After
  public void cleanupScratchDir() throws Exception {
    deleteAllUnder(scratchRoot);
  }

  private void deleteAllUnder(String path) throws IOException {
    if (new File(scratchRoot).exists()) {
      runCommand("cmd.exe /c rd /s /q \"" + scratchRoot + "\"");
    }
  }

  // Do not use WindowsFileSystem.createDirectoryJunction but reimplement junction creation here.
  // If that method were buggy, using it here would compromise the test.
  private void createJunctions(Map<String, String> links) throws Exception {
    List<String> args = new ArrayList<>();
    boolean first = true;

    // Shell out to cmd.exe to create all junctions in one go.
    // Running "cmd.exe /c command1 arg1 arg2 && command2 arg1 ... argN && ..." will run all
    // commands within one cmd.exe invocation.
    for (Map.Entry<String, String> e : links.entrySet()) {
      if (first) {
        args.add("cmd.exe /c");
        first = false;
      } else {
        args.add("&&");
      }

      args.add(
          String.format(
              "mklink /j \"%s/%s\" \"%s/%s\"", scratchRoot, e.getKey(), scratchRoot, e.getValue()));
    }
    runCommand(args);
  }

  private void runCommand(List<String> args) throws IOException {
    runCommand(Joiner.on(' ').join(args));
  }

  private void runCommand(String cmd) throws IOException {
    Process p = Runtime.getRuntime().exec(cmd);
    try {
      // Wait no more than 5 seconds to create all junctions.
      p.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      fail("Failed to execute command; cmd: " + cmd);
    }
    assertWithMessage("Command failed: " + cmd).that(p.exitValue()).isEqualTo(0);
  }

  private Path scratchDir(String path) throws IOException {
    return Files.createDirectories(new File(scratchRoot + "/" + path).toPath());
  }

  private void scratchFile(String path, String... contents) throws IOException {
    File fd = new File(scratchRoot + "/" + path);
    Files.createDirectories(fd.toPath().getParent());
    try (FileWriter w = new FileWriter(fd)) {
      for (String line : contents) {
        w.write(line);
        w.write('\n');
      }
    }
  }

  @Test
  public void testMockJunctionCreation() throws Exception {
    String root = scratchDir("dir").getParent().toString();
    scratchFile("dir/file.txt", "hello");
    createJunctions(ImmutableMap.of("junc", "dir"));
    String[] children = new File(root + "/junc").list();
    assertThat(children).isNotNull();
    assertThat(children).hasLength(1);
    assertThat(Arrays.asList(children)).containsExactly("file.txt");
  }

  @Test
  public void testIsJunction() throws Exception {
    final Map<String, String> junctions = new HashMap<>();
    junctions.put("shrtpath/a", "shrttrgt");
    junctions.put("shrtpath/b", "longtargetpath");
    junctions.put("shrtpath/c", "longta~1");
    junctions.put("longlinkpath/a", "shrttrgt");
    junctions.put("longlinkpath/b", "longtargetpath");
    junctions.put("longlinkpath/c", "longta~1");
    junctions.put("abbrev~1/a", "shrttrgt");
    junctions.put("abbrev~1/b", "longtargetpath");
    junctions.put("abbrev~1/c", "longta~1");

    String root = scratchDir("shrtpath").getParent().toAbsolutePath().toString();
    scratchDir("longlinkpath");
    scratchDir("abbreviated");
    scratchDir("control/a");
    scratchDir("control/b");
    scratchDir("control/c");

    scratchFile("shrttrgt/file1.txt", "hello");
    scratchFile("longtargetpath/file2.txt", "hello");

    createJunctions(junctions);

    assertThat(WindowsFileOperations.isJunction(root + "/shrtpath/a")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/shrtpath/b")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/shrtpath/c")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/longlinkpath/a")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/longlinkpath/b")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/longlinkpath/c")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/longli~1/a")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/longli~1/b")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/longli~1/c")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/abbreviated/a")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/abbreviated/b")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/abbreviated/c")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/abbrev~1/a")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/abbrev~1/b")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/abbrev~1/c")).isTrue();
    assertThat(WindowsFileOperations.isJunction(root + "/control/a")).isFalse();
    assertThat(WindowsFileOperations.isJunction(root + "/control/b")).isFalse();
    assertThat(WindowsFileOperations.isJunction(root + "/control/c")).isFalse();
    assertThat(WindowsFileOperations.isJunction(root + "/shrttrgt/file1.txt")).isFalse();
    assertThat(WindowsFileOperations.isJunction(root + "/longtargetpath/file2.txt")).isFalse();
    assertThat(WindowsFileOperations.isJunction(root + "/longta~1/file2.txt")).isFalse();
    try {
      WindowsFileOperations.isJunction(root + "/non-existent");
      fail("expected to throw");
    } catch (IOException e) {
      assertThat(e.getMessage()).contains("GetFileAttributesA");
    }
    assertThat(Arrays.asList(new File(root + "/shrtpath/a").list())).containsExactly("file1.txt");
    assertThat(Arrays.asList(new File(root + "/shrtpath/b").list())).containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/shrtpath/c").list())).containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/longlinkpath/a").list()))
        .containsExactly("file1.txt");
    assertThat(Arrays.asList(new File(root + "/longlinkpath/b").list()))
        .containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/longlinkpath/c").list()))
        .containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/abbreviated/a").list()))
        .containsExactly("file1.txt");
    assertThat(Arrays.asList(new File(root + "/abbreviated/b").list()))
        .containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/abbreviated/c").list()))
        .containsExactly("file2.txt");
  }
}
