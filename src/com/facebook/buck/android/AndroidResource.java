/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RecordFileSha1Step;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.AndroidManifestReader;
import com.facebook.buck.util.DefaultAndroidManifestReader;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

/**
 * An object that represents the resources of an android library.
 * <p>
 * Suppose this were a rule defined in <code>src/com/facebook/feed/BUILD</code>:
 * <pre>
 * android_resources(
 *   name = 'res',
 *   res = 'res',
 *   assets = 'buck-assets',
 *   deps = [
 *     '//first-party/orca/lib-ui:lib-ui',
 *   ],
 * )
 * </pre>
 */
public class AndroidResource extends AbstractBuildRule
    implements AbiRule, HasAndroidResourceDeps, InitializableFromDisk<AndroidResource.BuildOutput>,
    AndroidPackageable {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, LIBRARY);

  @VisibleForTesting
  static final String METADATA_KEY_FOR_ABI = "ANDROID_RESOURCE_ABI_KEY";

  @VisibleForTesting
  static final String METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE = "METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE";

  /** {@link Function} that invokes {@link #getRes()} on an {@link AndroidResource}. */
  private static final Function<HasAndroidResourceDeps, Path> GET_RES_FOR_RULE =
      new Function<HasAndroidResourceDeps, Path>() {
    @Override
    @Nullable
    public Path apply(HasAndroidResourceDeps rule) {
      return rule.getRes();
    }
  };

  @Nullable
  private final Path res;

  /** contents of {@code res} under version control (i.e., not generated by another rule). */
  private final ImmutableSortedSet<Path> resSrcs;

  @Nullable
  private final Path assets;

  /** contents of {@code assets} under version control (i.e., not generated by another rule). */
  private final ImmutableSortedSet<Path> assetsSrcs;

  @Nullable
  private final Path pathToTextSymbolsDir;

  @Nullable
  private final Path pathToTextSymbolsFile;

  @Nullable
  private final SourcePath manifestFile;

  private final boolean hasWhitelistedStrings;

  private final ImmutableSortedSet<BuildRule> deps;
  private final Supplier<ImmutableList<HasAndroidResourceDeps>> transitiveAndroidResourceDeps;

  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  /**
   * This is the original {@code package} argument passed to this rule.
   */
  @Nullable
  private final String rDotJavaPackageArgument;

  /**
   * Supplier that returns the package for the Java class generated for the resources in
   * {@link #res}, if any. The value for this supplier is determined, as follows:
   * <ul>
   *   <li>If the user specified a {@code package} argument, the supplier will return that value.
   *   <li>Failing that, when the rule is built, it will parse the package from the file specified
   *       by the {@code manifest} so that it can be returned by this supplier. (Note this also
   *       needs to work correctly if the rule is initialized from disk.)
   *   <li>In all other cases (e.g., both {@code package} and {@code manifest} are unspecified), the
   *       behavior is undefined.
   * </ul>
   */
  private final Supplier<String> rDotJavaPackageSupplier;

  private final AtomicReference<String> rDotJavaPackage;

  protected AndroidResource(
      BuildRuleParams buildRuleParams,
      final ImmutableSortedSet<BuildRule> deps,
      @Nullable final Path res,
      ImmutableSortedSet<Path> resSrcs,
      @Nullable String rDotJavaPackageArgument,
      @Nullable Path assets,
      ImmutableSortedSet<Path> assetsSrcs,
      @Nullable SourcePath manifestFile,
      boolean hasWhitelistedStrings) {
    super(buildRuleParams);
    if (res != null && rDotJavaPackageArgument == null && manifestFile == null) {
      throw new HumanReadableException(
          "When the 'res' is specified for android_resource() %s, at least one of 'package' or " +
              "'manifest' must be specified.",
          getBuildTarget());
    }

    this.res = res;
    this.resSrcs = Preconditions.checkNotNull(resSrcs);
    this.assets = assets;
    this.assetsSrcs = Preconditions.checkNotNull(assetsSrcs);
    this.manifestFile = manifestFile;
    this.hasWhitelistedStrings = hasWhitelistedStrings;

    BuildTarget buildTarget = buildRuleParams.getBuildTarget();
    if (res == null) {
      pathToTextSymbolsDir = null;
      pathToTextSymbolsFile = null;
    } else {
      pathToTextSymbolsDir = BuildTargets.getGenPath(buildTarget, "__%s_text_symbols__");
      pathToTextSymbolsFile = pathToTextSymbolsDir.resolve("R.txt");
    }

    this.deps = Preconditions.checkNotNull(deps);
    this.transitiveAndroidResourceDeps = Suppliers.memoize(
        new Supplier<ImmutableList<HasAndroidResourceDeps>>() {
          @Override
          public ImmutableList<HasAndroidResourceDeps> get() {
            ImmutableList.Builder<HasAndroidResourceDeps> resDeps = ImmutableList.builder();
            if (res != null) {
              resDeps.add(AndroidResource.this);
            }
            resDeps.addAll(UberRDotJavaUtil.getAndroidResourceDeps(getDeps()));
            return resDeps.build();
          }
        });

    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);

    this.rDotJavaPackageArgument = rDotJavaPackageArgument;
    this.rDotJavaPackage = new AtomicReference<>();
    if (rDotJavaPackageArgument != null) {
      this.rDotJavaPackage.set(rDotJavaPackageArgument);
    }

    this.rDotJavaPackageSupplier = new Supplier<String>() {
      @Override
      public String get() {
        String rDotJavaPackage = AndroidResource.this.rDotJavaPackage.get();
        if (rDotJavaPackage != null) {
          return rDotJavaPackage;
        } else {
          throw new RuntimeException(String.format(
              "rDotJavaPackage for %s was requested before it was made available.",
              AndroidResource.this.getBuildTarget()));
        }
      }
    };

  }

  @Override
  public ImmutableCollection<Path> getInputsToCompareToOutput() {
    ImmutableSortedSet.Builder<Path> inputs = ImmutableSortedSet.naturalOrder();

    // This should include the res/ and assets/ folders.
    inputs.addAll(resSrcs);
    inputs.addAll(assetsSrcs);

    // manifest file is optional.
    if (manifestFile != null) {
      inputs.addAll(
          SourcePaths.filterInputsToCompareToOutput(Collections.singleton(manifestFile)));
    }

    return inputs.build();
  }

  @Override
  @Nullable
  public Path getRes() {
    return res;
  }

  @Override
  public boolean hasWhitelistedStrings() {
    return hasWhitelistedStrings;
  }

  @Override
  @Nullable
  public Path getAssets() {
    return assets;
  }

  @Nullable
  public SourcePath getManifestFile() {
    return manifestFile;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      final BuildableContext buildableContext) {
    // If there is no res directory, then there is no R.java to generate.
    // TODO(mbolin): Change android_resources() so that 'res' is required.
    if (getRes() == null) {
      // Normally, the ABI key of a resource rule is a hash of the R.txt file that it creates.
      // That R.txt essentially combines all the R.txt files generated by transitive dependencies of
      // this rule. So, in this case (when we don't have the R.txt), the right ABI key is the
      // ABI key for deps.
      buildableContext.addMetadata(METADATA_KEY_FOR_ABI, getAbiKeyForDeps().getHash());
      return ImmutableList.of();
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(new MakeCleanDirectoryStep(pathToTextSymbolsDir));

    // If the 'package' was not specified for this android_resource(), then attempt to parse it
    // from the AndroidManifest.xml.
    if (rDotJavaPackageArgument == null) {
      steps.add(new AbstractExecutionStep("extract_android_package") {
        @Override
        public int execute(ExecutionContext context) {
          Preconditions.checkNotNull(
              manifestFile,
              "manifestFile cannot be null when res is non-null and rDotJavaPackageArgument is " +
                  "null. This should already be enforced by the constructor.");

          AndroidManifestReader androidManifestReader;
          try {
            androidManifestReader = DefaultAndroidManifestReader.forPath(
                manifestFile.resolve(), context.getProjectFilesystem());
          } catch (IOException e) {
            context.logError(e, "Failed to create AndroidManifestReader for %s.", manifestFile);
            return 1;
          }

          String rDotJavaPackageFromAndroidManifest = androidManifestReader.getPackage();
          if (rDotJavaPackageFromAndroidManifest == null) {
            context.logError(new Throwable(), "Failed to read package from %s.", manifestFile);
            return 1;
          }

          AndroidResource.this.rDotJavaPackage.set(rDotJavaPackageFromAndroidManifest);
          buildableContext.addMetadata(
              METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE,
              rDotJavaPackageFromAndroidManifest);
          return 0;
        }
      });
    }

    // Searching through the deps, find any additional res directories to pass to aapt.
    ImmutableList<Path> resDirectories = FluentIterable.from(transitiveAndroidResourceDeps.get())
        .transform(GET_RES_FOR_RULE)
        .toList();

    Path dummyManifestFile = BuildTargets.getGenPath(
        getBuildTarget(), "__%s_dummy_manifest/AndroidManifest.xml");
    steps.addAll(GenRDotTxtUtil.createSteps(
        resDirectories,
        pathToTextSymbolsDir,
        rDotJavaPackageSupplier,
        /* isTempRDotJava */ true,
        dummyManifestFile));

    buildableContext.recordArtifact(pathToTextSymbolsFile);

    steps.add(new RecordFileSha1Step(
        pathToTextSymbolsFile,
        METADATA_KEY_FOR_ABI,
        buildableContext));

    return steps.build();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .set("rDotJavaPackage", rDotJavaPackageArgument)
        .set("hasWhitelistedStrings", hasWhitelistedStrings);
  }

  @Override
  @Nullable
  public Path getPathToOutputFile() {
    return Optional.fromNullable(pathToTextSymbolsFile).orNull();
  }

  @Override
  @Nullable
  public Path getPathToTextSymbolsFile() {
    return pathToTextSymbolsFile;
  }

  @Override
  public Sha1HashCode getTextSymbolsAbiKey() {
    return buildOutputInitializer.getBuildOutput().textSymbolsAbiKey;
  }

  @Override
  public String getRDotJavaPackage() {
    String rDotJavaPackage = rDotJavaPackageSupplier.get();
    if (rDotJavaPackage == null) {
      throw new RuntimeException("No package for " + getBuildTarget());
    }
    return rDotJavaPackage;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    // We hash the transitive dependencies and not just the first order deps because we pass these
    // to aapt to generate R.java/R.txt.
    // Transitive dependencies includes this rule itself; filter it out.
    return HasAndroidResourceDeps.ABI_HASHER.apply(Iterables.filter(
        transitiveAndroidResourceDeps.get(),
        Predicates.not(
            Predicates.equalTo((HasAndroidResourceDeps) AndroidResource.this))));
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    Sha1HashCode sha1HashCode = onDiskBuildInfo.getHash(METADATA_KEY_FOR_ABI).get();
    Optional<String> rDotJavaPackageFromAndroidManifest = onDiskBuildInfo.getValue(
        METADATA_KEY_FOR_R_DOT_JAVA_PACKAGE);
    if (rDotJavaPackageFromAndroidManifest.isPresent()) {
      rDotJavaPackage.set(rDotJavaPackageFromAndroidManifest.get());
    }
    return new BuildOutput(sha1HashCode);
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(deps);
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    if (res != null) {
      if (hasWhitelistedStrings) {
        collector.addStringWhitelistedResourceDirectory(
            getBuildTarget(),
            res,
            rDotJavaPackageSupplier);
      } else {
        collector.addResourceDirectory(getBuildTarget(), res, rDotJavaPackageSupplier);
      }
    }
    if (assets != null) {
      collector.addAssetsDirectory(getBuildTarget(), assets);
    }
    if (manifestFile != null) {
      collector.addManifestFile(getBuildTarget(), manifestFile.resolve());
    }
  }

  public static class BuildOutput {
    private final Sha1HashCode textSymbolsAbiKey;

    public BuildOutput(Sha1HashCode textSymbolsAbiKey) {
      this.textSymbolsAbiKey = Preconditions.checkNotNull(textSymbolsAbiKey);
    }
  }
}
