// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.EventHandler;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkValue;

/**
 * An interface for language-specific configurations.
 *
 * <p>Implementations must have a constructor that takes a single {@link BuildOptions} argument. If
 * the constructor reads any {@link FragmentOptions} from this argument, the fragment must declare
 * them via {@link RequiresOptions}.
 *
 * <p>All implementations must be immutable and communicate this as clearly as possible (e.g.
 * declare {@link com.google.common.collect.ImmutableList} signatures on their interfaces vs. {@link
 * List}). This is because fragment instances may be shared across configurations.
 *
 * <p>Fragments are Starlark values, as returned by {@code ctx.fragments.android}, for example.
 */
@Immutable
public abstract class Fragment implements StarlarkValue {

  /**
   * When a fragment doesn't want to be part of the configuration (for example, when its required
   * options are missing and the fragment determines this means the configuration doesn't need it),
   * it should override this method.
   */
  public boolean shouldInclude() {
    return true;
  }

  @Override
  public boolean isImmutable() {
    return true; // immutable and Starlark-hashable
  }

  /**
   * Validates the options for this Fragment. Issues warnings for the use of deprecated options, and
   * warnings or errors for any option settings that conflict.
   */
  @SuppressWarnings("unused")
  public void reportInvalidOptions(EventHandler reporter, BuildOptions buildOptions) {}

  /**
   * Returns a fragment of the output directory name for this configuration. The output directory
   * for the whole configuration contains all the short names by all fragments.
   */
  @Nullable
  public String getOutputDirectoryName() {
    return null;
  }

  /** Returns the option classes needed to create a fragment. */
  public static ImmutableSet<Class<? extends FragmentOptions>> requiredOptions(
      Class<? extends Fragment> fragmentClass) {
    return fragmentClass.isAnnotationPresent(RequiresOptions.class)
        ? ImmutableSet.copyOf(fragmentClass.getAnnotation(RequiresOptions.class).options())
        : ImmutableSet.of();
  }
}
