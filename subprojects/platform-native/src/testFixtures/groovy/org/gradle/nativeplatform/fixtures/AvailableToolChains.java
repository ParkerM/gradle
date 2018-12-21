/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.nativeplatform.fixtures;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.gradle.api.internal.file.TestFiles;
import org.gradle.api.specs.Spec;
import org.gradle.integtests.fixtures.AbstractContextualMultiVersionSpecRunner;
import org.gradle.internal.nativeintegration.ProcessEnvironment;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.nativeplatform.fixtures.msvcpp.VisualStudioLocatorTestFixture;
import org.gradle.nativeplatform.fixtures.msvcpp.VisualStudioVersion;
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform;
import org.gradle.nativeplatform.toolchain.Clang;
import org.gradle.nativeplatform.toolchain.Gcc;
import org.gradle.nativeplatform.toolchain.Swiftc;
import org.gradle.nativeplatform.toolchain.VisualCpp;
import org.gradle.nativeplatform.toolchain.internal.gcc.metadata.GccMetadata;
import org.gradle.nativeplatform.toolchain.internal.gcc.metadata.GccMetadataProvider;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.VisualStudioInstall;
import org.gradle.nativeplatform.toolchain.internal.swift.metadata.SwiftcMetadata;
import org.gradle.nativeplatform.toolchain.internal.swift.metadata.SwiftcMetadataProvider;
import org.gradle.nativeplatform.toolchain.plugins.ClangCompilerPlugin;
import org.gradle.nativeplatform.toolchain.plugins.GccCompilerPlugin;
import org.gradle.nativeplatform.toolchain.plugins.MicrosoftVisualCppCompilerPlugin;
import org.gradle.nativeplatform.toolchain.plugins.SwiftCompilerPlugin;
import org.gradle.platform.base.internal.toolchain.SearchResult;
import org.gradle.test.fixtures.file.TestFile;
import org.gradle.testfixtures.internal.NativeServicesTestFixture;
import org.gradle.util.CollectionUtils;
import org.gradle.util.GUtil;
import org.gradle.util.VersionNumber;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.gradle.nativeplatform.fixtures.msvcpp.VisualStudioVersion.VISUALSTUDIO_2012;
import static org.gradle.nativeplatform.fixtures.msvcpp.VisualStudioVersion.VISUALSTUDIO_2013;
import static org.gradle.nativeplatform.fixtures.msvcpp.VisualStudioVersion.VISUALSTUDIO_2015;
import static org.gradle.nativeplatform.fixtures.msvcpp.VisualStudioVersion.VISUALSTUDIO_2017;

public class AvailableToolChains {
    private static final Comparator<ToolChainCandidate> LATEST_FIRST = Collections.reverseOrder(new Comparator<ToolChainCandidate>() {
        @Override
        public int compare(ToolChainCandidate toolchain1, ToolChainCandidate toolchain2) {
            return toolchain1.getVersion().compareTo(toolchain2.getVersion());
        }
    });
    private static List<ToolChainCandidate> toolChains;

    /**
     * Locates the tool chain that would be used as the default for the current machine, if any.
     *
     * @return null if there is no such tool chain.
     */
    @Nullable
    public static InstalledToolChain getDefaultToolChain() {
        for (ToolChainCandidate toolChain : getToolChains()) {
            if (toolChain.isAvailable()) {
                return (InstalledToolChain) toolChain;
            }
        }
        return null;
    }

    /**
     * Locates a tool chain that meets the given criteria, if any.
     *
     * @return null if there is no such tool chain.
     */
    @Nullable
    public static InstalledToolChain getToolChain(ToolChainRequirement requirement) {
        for (ToolChainCandidate toolChainCandidate : getToolChains()) {
            if (toolChainCandidate.meets(requirement)) {
                assert toolChainCandidate.isAvailable();
                return (InstalledToolChain) toolChainCandidate;
            }
        }
        return null;
    }

    /**
     * @return A list of all known tool chains for this platform. Includes those tool chains that are not available on the current machine.
     */
    public static List<ToolChainCandidate> getToolChains() {
        if (toolChains == null) {
            List<ToolChainCandidate> compilers = new ArrayList<ToolChainCandidate>();
            if (OperatingSystem.current().isWindows()) {
                compilers.addAll(findVisualCpps());
                compilers.add(findMinGW());
                compilers.add(findCygwin());
            } else if (OperatingSystem.current().isMacOsX()) {
                compilers.addAll(findClangs(true));
                compilers.addAll(findGccs(false));
                compilers.addAll(findSwiftcs());
            } else {
                compilers.addAll(findGccs(true));
                compilers.addAll(findClangs(false));
                compilers.addAll(findSwiftcs());
            }
            toolChains = compilers;
        }
        return toolChains;
    }

    static private List<ToolChainCandidate> findClangs(boolean mustFind) {
        GccMetadataProvider versionDeterminer = GccMetadataProvider.forClang(TestFiles.execActionFactory());

        Set<File> clangCandidates = ImmutableSet.copyOf(OperatingSystem.current().findAllInPath("clang"));
        List<ToolChainCandidate> toolChains = Lists.newArrayList();
        if (!clangCandidates.isEmpty()) {
            File firstInPath = clangCandidates.iterator().next();
            for (File candidate : clangCandidates) {
                SearchResult<GccMetadata> version = versionDeterminer.getCompilerMetaData(candidate, Collections.<String>emptyList(), Collections.<File>emptyList());
                if (version.isAvailable()) {
                    InstalledClang clang = new InstalledClang(version.getComponent().getVersion());
                    if (!candidate.equals(firstInPath)) {
                        // Not the first g++ in the path, needs the path variable updated
                        clang.inPath(candidate.getParentFile());
                    }
                    toolChains.add(clang);
                }
            }
        }

        if (mustFind && toolChains.isEmpty()) {
            toolChains.add(new UnavailableToolChain(ToolFamily.CLANG));
        }

        toolChains.sort(LATEST_FIRST);

        return toolChains;
    }

    static private boolean isTestableVisualStudioVersion(final VersionNumber version) {
        return getVisualStudioVersion(version) != null;
    }

    static private VisualStudioVersion getVisualStudioVersion(final VersionNumber version) {
        return CollectionUtils.findFirst(VisualStudioVersion.values(), new Spec<VisualStudioVersion>() {
            @Override
            public boolean isSatisfiedBy(VisualStudioVersion candidate) {
                return candidate.getVersion().getMajor() == version.getMajor();
            }
        });
    }

    static private List<ToolChainCandidate> findVisualCpps() {
        // Search in the standard installation locations
        final List<? extends VisualStudioInstall> searchResults = VisualStudioLocatorTestFixture.getVisualStudioLocator().locateAllComponents();

        List<ToolChainCandidate> toolChains = Lists.newArrayList();

        for (VisualStudioInstall install : searchResults) {
            if (isTestableVisualStudioVersion(install.getVersion())) {
                toolChains.add(new InstalledVisualCpp(getVisualStudioVersion(install.getVersion())).withInstall(install));
            }
        }

        if (toolChains.isEmpty()) {
            toolChains.add(new UnavailableToolChain(ToolFamily.VISUAL_CPP));
        }

        toolChains.sort(LATEST_FIRST);

        return toolChains;
    }

    static private ToolChainCandidate findMinGW() {
        // Search in the standard installation locations
        File compilerExe = new File("C:/MinGW/bin/g++.exe");
        if (compilerExe.isFile()) {
            return new InstalledWindowsGcc(ToolFamily.MINGW_GCC, VersionNumber.UNKNOWN).inPath(compilerExe.getParentFile());
        }

        return new UnavailableToolChain(ToolFamily.MINGW_GCC);
    }

    static private ToolChainCandidate findCygwin() {
        // Search in the standard installation locations and construct
        File compiler64Exe = new File("C:/cygwin64/bin/g++.exe");
        if (compiler64Exe.isFile()) {
            File compiler32Exe = new File("C:/cygwin/bin/g++.exe");
            if (compiler32Exe.isFile()) {
                return new InstalledCygwinGcc64(ToolFamily.CYGWIN_GCC_64, VersionNumber.UNKNOWN, compiler64Exe.getParentFile(), compiler32Exe.getParentFile());
            } else {
                return new UnavailableToolChain(ToolFamily.CYGWIN_GCC);
            }
        }

        // Fall back to just 32-bit toolchain if available
        File compilerExe = new File("C:/cygwin/bin/g++.exe");
        if (compilerExe.isFile()) {
            return new InstalledWindowsGcc(ToolFamily.CYGWIN_GCC, VersionNumber.UNKNOWN).inPath(compilerExe.getParentFile());
        }

        return new UnavailableToolChain(ToolFamily.CYGWIN_GCC);
    }

    static private List<ToolChainCandidate> findGccs(boolean mustFind) {
        GccMetadataProvider versionDeterminer = GccMetadataProvider.forGcc(TestFiles.execActionFactory());

        Set<File> gppCandidates = ImmutableSet.copyOf(OperatingSystem.current().findAllInPath("g++"));
        List<ToolChainCandidate> toolChains = Lists.newArrayList();
        if (!gppCandidates.isEmpty()) {
            File firstInPath = gppCandidates.iterator().next();
            for (File candidate : gppCandidates) {
                SearchResult<GccMetadata> version = versionDeterminer.getCompilerMetaData(candidate, Collections.<String>emptyList(), Collections.<File>emptyList());
                if (version.isAvailable()) {
                    InstalledGcc gcc = new InstalledGcc(ToolFamily.GCC, version.getComponent().getVersion());
                    if (!candidate.equals(firstInPath)) {
                        // Not the first g++ in the path, needs the path variable updated
                        gcc.inPath(candidate.getParentFile());
                    }
                    toolChains.add(gcc);
                }
            }
        }

        if (mustFind && toolChains.isEmpty()) {
            toolChains.add(new UnavailableToolChain(ToolFamily.GCC));
        }

        toolChains.sort(LATEST_FIRST);

        return toolChains;
    }

    static List<ToolChainCandidate> findSwiftcs() {
        List<ToolChainCandidate> toolChains = Lists.newArrayList();

        SwiftcMetadataProvider versionDeterminer = new SwiftcMetadataProvider(TestFiles.execActionFactory());

        // On Linux, we assume swift is installed into /opt/swift
        File rootSwiftInstall = new File("/opt/swift");
        File[] candidates = GUtil.elvis(rootSwiftInstall.listFiles(new FileFilter() {
            @Override
            public boolean accept(File swiftInstall) {
                return swiftInstall.isDirectory() && !swiftInstall.getName().equals("latest");
            }
        }), new File[0]);

        for (File swiftInstall : candidates) {
            File swiftc = new File(swiftInstall, "/usr/bin/swiftc");
            SearchResult<SwiftcMetadata> version = versionDeterminer.getCompilerMetaData(swiftc, Collections.<String>emptyList(), Collections.<File>emptyList());
            if (version.isAvailable()) {
                File binDir = swiftc.getParentFile();
                toolChains.add(new InstalledSwiftc(binDir, version.getComponent().getVersion()).inPath(binDir, new File("/usr/bin")));
            }
        }

        List<File> swiftcCandidates = OperatingSystem.current().findAllInPath("swiftc");
        for (File candidate : swiftcCandidates) {
            SearchResult<SwiftcMetadata> version = versionDeterminer.getCompilerMetaData(candidate, Collections.<String>emptyList(), Collections.<File>emptyList());
            if (version.isAvailable()) {
                File binDir = candidate.getParentFile();
                InstalledSwiftc swiftc = new InstalledSwiftc(binDir, version.getComponent().getVersion());
                swiftc.inPath(binDir, new File("/usr/bin"));
                toolChains.add(swiftc);
            }
        }

        if (toolChains.isEmpty()) {
            toolChains.add(new UnavailableToolChain(ToolFamily.SWIFTC));
        } else {
            toolChains.sort(LATEST_FIRST);
        }

        return toolChains;
    }

    public enum ToolFamily {
        GCC("gcc"),
        CLANG("clang"),
        VISUAL_CPP("visual c++"),
        MINGW_GCC("mingw"),
        CYGWIN_GCC("gcc cygwin"),
        CYGWIN_GCC_64("gcc cygwin64"),
        SWIFTC("swiftc");

        private final String displayName;

        ToolFamily(String displayName) {
            this.displayName = displayName;
        }
    }

    public static abstract class ToolChainCandidate implements AbstractContextualMultiVersionSpecRunner.VersionedTool {
        @Override
        public String toString() {
            return getDisplayName();
        }

        public abstract String getDisplayName();

        public abstract ToolFamily getFamily();

        public abstract VersionNumber getVersion();

        public abstract boolean isAvailable();

        public abstract boolean meets(ToolChainRequirement requirement);

        public abstract void initialiseEnvironment();

        public abstract void resetEnvironment();

    }

    public abstract static class InstalledToolChain extends ToolChainCandidate {
        private static final ProcessEnvironment PROCESS_ENVIRONMENT = NativeServicesTestFixture.getInstance().get(ProcessEnvironment.class);
        protected final List<File> pathEntries = new ArrayList<File>();
        private final ToolFamily family;
        private final VersionNumber version;
        protected final String pathVarName;
        private final String objectFileNameSuffix;

        private String originalPath;

        public InstalledToolChain(ToolFamily family, VersionNumber version) {
            this.family = family;
            this.version = version;
            this.pathVarName = OperatingSystem.current().getPathVar();
            this.objectFileNameSuffix = OperatingSystem.current().isWindows() ? ".obj" : ".o";
        }

        InstalledToolChain inPath(File... pathEntries) {
            Collections.addAll(this.pathEntries, pathEntries);
            return this;
        }

        @Override
        public String getDisplayName() {
            return family.displayName + (version == VersionNumber.UNKNOWN ? "" : " " + version.toString());
        }

        @Override
        public ToolFamily getFamily() {
            return family;
        }

        @Override
        public VersionNumber getVersion() {
            return version;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        public String getTypeDisplayName() {
            return getDisplayName().replaceAll("\\s+\\d+(\\.\\d+)*(\\s+\\(\\d+(\\.\\d+)*\\))?$", "");
        }

        public abstract String getInstanceDisplayName();

        public ExecutableFixture executable(Object path) {
            return new ExecutableFixture(new TestFile(OperatingSystem.current().getExecutableName(path.toString())), this);
        }

        public LinkerOptionsFixture linkerOptionsFor(Object path) {
            return new LinkerOptionsFixture(new TestFile(path.toString()));
        }

        public TestFile objectFile(Object path) {
            return new TestFile(path.toString() + objectFileNameSuffix);
        }

        public SharedLibraryFixture sharedLibrary(Object path) {
            return new SharedLibraryFixture(new TestFile(OperatingSystem.current().getSharedLibraryName(path.toString())), this);
        }

        public StaticLibraryFixture staticLibrary(Object path) {
            return new StaticLibraryFixture(new TestFile(OperatingSystem.current().getStaticLibraryName(path.toString())), this);
        }

        public NativeBinaryFixture resourceOnlyLibrary(Object path) {
            return new NativeBinaryFixture(new TestFile(OperatingSystem.current().getSharedLibraryName(path.toString())), this);
        }

        /**
         * Initialise the process environment so that this tool chain is visible to the default discovery mechanism that the
         * plugin uses (eg add the compiler to the PATH).
         */
        public void initialiseEnvironment() {
            String compilerPath = Joiner.on(File.pathSeparator).join(pathEntries);

            if (compilerPath.length() > 0) {
                originalPath = System.getenv(pathVarName);
                String path = compilerPath + File.pathSeparator + originalPath;
                System.out.println(String.format("Using path %s", path));
                PROCESS_ENVIRONMENT.setEnvironmentVariable(pathVarName, path);
            }
        }

        public void resetEnvironment() {
            if (originalPath != null) {
                PROCESS_ENVIRONMENT.setEnvironmentVariable(pathVarName, originalPath);
            }
        }

        public abstract String getBuildScriptConfig();

        public abstract String getImplementationClass();

        public abstract String getPluginClass();

        public boolean isVisualCpp() {
            return false;
        }

        public List<File> getPathEntries() {
            return pathEntries;
        }

        /**
         * The environment required to execute a binary created by this toolchain.
         */
        public List<String> getRuntimeEnv() {
            // Toolchains should be linking against stuff in the standard locations
            return Collections.emptyList();
        }

        protected List<String> toRuntimeEnv() {
            if (pathEntries.isEmpty()) {
                return Collections.emptyList();
            }

            String path = Joiner.on(File.pathSeparator).join(pathEntries) + File.pathSeparator + System.getenv(pathVarName);
            return Collections.singletonList(pathVarName + "=" + path);
        }

        public String getId() {
            return getDisplayName().replaceAll("\\W", "");
        }

        public abstract String getUnitTestPlatform();

        @Override
        public boolean matches(String criteria) {
            // Implement this if you need to specify individual toolchains via "org.gradle.integtest.versions"
            throw new UnsupportedOperationException();
        }
    }

    public static abstract class GccCompatibleToolChain extends InstalledToolChain {
        protected GccCompatibleToolChain(ToolFamily family, VersionNumber version) {
            super(family, version);
        }

        protected File find(String tool) {
            if (getPathEntries().isEmpty()) {
                return OperatingSystem.current().findInPath(tool);
            }
            return new File(getPathEntries().get(0), tool);
        }

        public File getLinker() {
            return getCCompiler();
        }

        public File getStaticLibArchiver() {
            return find("ar");
        }

        public abstract File getCppCompiler();

        public abstract File getCCompiler();

        @Override
        public String getUnitTestPlatform() {
            if (OperatingSystem.current().isMacOsX()) {
                return "osx";
            }
            if (OperatingSystem.current().isLinux()) {
                return "linux";
            }
            return "UNKNOWN";
        }
    }

    public static class InstalledGcc extends GccCompatibleToolChain {
        public InstalledGcc(ToolFamily family, VersionNumber version) {
            super(family, version);
        }

        @Override
        public boolean meets(ToolChainRequirement requirement) {
            return requirement == ToolChainRequirement.GCC || requirement == ToolChainRequirement.GCC_COMPATIBLE || requirement == ToolChainRequirement.AVAILABLE || requirement == ToolChainRequirement.SUPPORTS_32 || requirement == ToolChainRequirement.SUPPORTS_32_AND_64;
        }

        @Override
        public String getBuildScriptConfig() {
            String config = String.format("%s(%s)\n", getId(), getImplementationClass());
            for (File pathEntry : getPathEntries()) {
                config += String.format("%s.path file('%s')\n", getId(), pathEntry.toURI());
            }
            return config;
        }

        @Override
        public File getCppCompiler() {
            return find("g++");
        }

        @Override
        public File getCCompiler() {
            return find("gcc");
        }

        @Override
        public String getInstanceDisplayName() {
            return String.format("Tool chain '%s' (GNU GCC)", getId());
        }

        @Override
        public String getImplementationClass() {
            return Gcc.class.getSimpleName();
        }

        @Override
        public String getPluginClass() {
            return GccCompilerPlugin.class.getSimpleName();
        }

        @Override
        public String getId() {
            return "gcc";
        }
    }

    public static class InstalledWindowsGcc extends InstalledGcc {
        public InstalledWindowsGcc(ToolFamily family, VersionNumber version) {
            super(family, version);
        }

        /**
         * The environment required to execute a binary created by this toolchain.
         */
        @Override
        public List<String> getRuntimeEnv() {
            return toRuntimeEnv();
        }

        @Override
        public String getUnitTestPlatform() {
            if ("mingw".equals(getDisplayName())) {
                return "mingw";
            }
            if ("gcc cygwin".equals(getDisplayName())) {
                return "cygwin";
            }
            return "UNKNOWN";
        }

        @Override
        public boolean meets(ToolChainRequirement requirement) {
            switch (requirement) {
                case SUPPORTS_32:
                case WINDOWS_GCC:
                    return true;
                case SUPPORTS_32_AND_64:
                    return getFamily() == ToolFamily.CYGWIN_GCC_64;
                default:
                    return super.meets(requirement);
            }
        }

        @Override
        public String getId() {
            return getDisplayName().replaceAll("\\W", "");
        }
    }

    public static class InstalledCygwinGcc64 extends InstalledWindowsGcc {
        private final File cygwin32Path;
        private final File cygwin64Path;

        public InstalledCygwinGcc64(ToolFamily family, VersionNumber version, File cygwin32Path, File cygwin64Path) {
            super(family, version);
            this.cygwin32Path = cygwin32Path;
            this.cygwin64Path = cygwin64Path;
        }

        @Override
        public String getBuildScriptConfig() {
            String config = String.format("%s_32(%s) {\n", getId(), getImplementationClass());
            config += String.format("path file('%s')\n", cygwin32Path.toURI());
            config += "targets = ['windows_x86']";
            config += "}\n";
            config += String.format("%s_64(%s) {\n", getId(), getImplementationClass());
            config += String.format("path file('%s')\n", cygwin64Path.toURI());
            config += "targets = ['windows_x86_64']";
            config += "}\n";
            return config;
        }
    }

    public static class InstalledSwiftc extends InstalledToolChain {
        private final File binDir;
        private final VersionNumber compilerVersion;

        public InstalledSwiftc(File binDir, VersionNumber compilerVersion) {
            super(ToolFamily.SWIFTC, compilerVersion);
            this.binDir = binDir;
            this.compilerVersion = compilerVersion;
        }

        public File tool(String name) {
            return new File(binDir, name);
        }

        /**
         * The environment required to execute a binary created by this toolchain.
         */
        @Override
        public List<String> getRuntimeEnv() {
            return toRuntimeEnv();
        }

        @Override
        public String getInstanceDisplayName() {
            return String.format("Tool chain '%s' (Swiftc)", getId());
        }

        @Override
        public String getBuildScriptConfig() {
            String config = String.format("%s(%s)\n", getId(), getImplementationClass());
            for (File pathEntry : getPathEntries()) {
                config += String.format("%s.path file('%s')\n", getId(), pathEntry.toURI());
            }
            return config;
        }

        @Override
        public String getImplementationClass() {
            return Swiftc.class.getSimpleName();
        }

        @Override
        public String getPluginClass() {
            return SwiftCompilerPlugin.class.getSimpleName();
        }

        @Override
        public String getUnitTestPlatform() {
            return null;
        }

        @Override
        public boolean meets(ToolChainRequirement requirement) {
            return requirement == ToolChainRequirement.SWIFTC || (requirement == ToolChainRequirement.SWIFTC_3 && getVersion().getMajor() == 3) || (requirement == ToolChainRequirement.SWIFTC_4 && getVersion().getMajor() == 4);
        }
    }

    public static class InstalledVisualCpp extends InstalledToolChain {
        private final String displayVersion;
        private VersionNumber version;
        private File installDir;
        private File cppCompiler;

        public InstalledVisualCpp(VisualStudioVersion version) {
            super(ToolFamily.VISUAL_CPP, version.getVersion());
            this.displayVersion = version.getYear() + " (" + version.getVersion().toString() + ")";
        }

        @Override
        public String getDisplayName() {
            return getFamily().displayName + " " + displayVersion;
        }

        @Override
        public String getId() {
            return "visualCpp";
        }

        public InstalledVisualCpp withInstall(VisualStudioInstall install) {
            DefaultNativePlatform targetPlatform = new DefaultNativePlatform("default");
            installDir = install.getVisualStudioDir();
            version = install.getVersion();
            org.gradle.nativeplatform.toolchain.internal.msvcpp.VisualCpp visualCpp = install.getVisualCpp().forPlatform(targetPlatform);
            cppCompiler = visualCpp.getCompilerExecutable();
            pathEntries.addAll(visualCpp.getPath());
            return this;
        }

        @Override
        public boolean meets(ToolChainRequirement requirement) {
            switch (requirement) {
                case AVAILABLE:
                case VISUALCPP:
                case SUPPORTS_32:
                case SUPPORTS_32_AND_64:
                    return true;
                case VISUALCPP_2012_OR_NEWER:
                    return version.compareTo(VISUALSTUDIO_2012.getVersion()) >= 0;
                case VISUALCPP_2013:
                    return version.equals(VISUALSTUDIO_2013.getVersion());
                case VISUALCPP_2013_OR_NEWER:
                    return version.compareTo(VISUALSTUDIO_2013.getVersion()) >= 0;
                case VISUALCPP_2015:
                    return version.equals(VISUALSTUDIO_2015.getVersion());
                case VISUALCPP_2015_OR_NEWER:
                    return version.compareTo(VISUALSTUDIO_2015.getVersion()) >= 0;
                case VISUALCPP_2017:
                    return version.equals(VISUALSTUDIO_2017.getVersion());
                case VISUALCPP_2017_OR_NEWER:
                    return version.compareTo(VISUALSTUDIO_2017.getVersion()) >= 0;
                default:
                    return false;
            }
        }

        @Override
        public String getBuildScriptConfig() {
            String config = String.format("%s(%s)\n", getId(), getImplementationClass());
            if (installDir != null) {
                config += String.format("%s.installDir = file('%s')", getId(), installDir.toURI());
            }
            return config;
        }

        @Override
        public String getImplementationClass() {
            return VisualCpp.class.getSimpleName();
        }

        @Override
        public String getInstanceDisplayName() {
            return String.format("Tool chain '%s' (Visual Studio)", getId());
        }

        @Override
        public String getPluginClass() {
            return MicrosoftVisualCppCompilerPlugin.class.getSimpleName();
        }

        @Override
        public boolean isVisualCpp() {
            return true;
        }

        public VersionNumber getVersion() {
            return version;
        }

        public File getCppCompiler() {
            return cppCompiler;
        }

        @Override
        public TestFile objectFile(Object path) {
            return new TestFile(path.toString() + ".obj");
        }

        @Override
        public String getUnitTestPlatform() {
            switch (version.getMajor()) {
                case 12:
                    return "vs2013";
                case 14:
                    return "vs2015";
                default:
                    return "UNKNOWN";
            }
        }
    }

    public static class InstalledClang extends GccCompatibleToolChain {
        public InstalledClang(VersionNumber versionNumber) {
            super(ToolFamily.CLANG, versionNumber);
        }

        @Override
        public boolean meets(ToolChainRequirement requirement) {
            switch(requirement) {
                case AVAILABLE:
                case CLANG:
                case GCC_COMPATIBLE:
                    return true;
                case SUPPORTS_32:
                case SUPPORTS_32_AND_64:
                    return (!OperatingSystem.current().isMacOsX()) || getVersion().compareTo(VersionNumber.parse("10.0.0")) < 0;
                default:
                    return false;
            }
        }

        @Override
        public String getBuildScriptConfig() {
            String config = String.format("%s(%s)\n", getId(), getImplementationClass());
            for (File pathEntry : getPathEntries()) {
                config += String.format("%s.path file('%s')\n", getId(), pathEntry.toURI());
            }
            return config;
        }

        @Override
        public File getCppCompiler() {
            return find("clang++");
        }

        @Override
        public File getCCompiler() {
            return find("clang");
        }

        @Override
        public String getInstanceDisplayName() {
            return String.format("Tool chain '%s' (Clang)", getId());
        }

        @Override
        public String getImplementationClass() {
            return Clang.class.getSimpleName();
        }

        @Override
        public String getPluginClass() {
            return ClangCompilerPlugin.class.getSimpleName();
        }
    }

    public static class UnavailableToolChain extends ToolChainCandidate {
        private final ToolFamily family;

        public UnavailableToolChain(ToolFamily family) {
            this.family = family;
        }

        @Override
        public boolean meets(ToolChainRequirement requirement) {
            return false;
        }

        @Override
        public String getDisplayName() {
            return family.displayName;
        }

        @Override
        public ToolFamily getFamily() {
            return family;
        }

        @Override
        public VersionNumber getVersion() {
            return VersionNumber.UNKNOWN;
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void initialiseEnvironment() {
            throw new UnsupportedOperationException("Toolchain is not available");
        }

        @Override
        public void resetEnvironment() {
            throw new UnsupportedOperationException("Toolchain is not available");
        }

        @Override
        public boolean matches(String criteria) {
            return false;
        }
    }
}
