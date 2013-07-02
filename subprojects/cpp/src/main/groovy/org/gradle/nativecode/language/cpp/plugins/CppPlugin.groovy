/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.nativecode.language.cpp.plugins
import org.gradle.api.Incubating
import org.gradle.api.Plugin
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.BasePlugin
import org.gradle.nativecode.base.*
import org.gradle.nativecode.base.internal.NativeBinaryInternal
import org.gradle.nativecode.base.plugins.BinariesPlugin
import org.gradle.nativecode.base.tasks.*
import org.gradle.nativecode.language.cpp.CppSourceSet
import org.gradle.nativecode.language.cpp.tasks.CppCompile
import org.gradle.nativecode.toolchain.plugins.GppCompilerPlugin
import org.gradle.nativecode.toolchain.plugins.MicrosoftVisualCppPlugin
/**
 * A plugin for projects wishing to build custom components from C++ sources.
 * <p>Automatically includes {@link MicrosoftVisualCppPlugin} and {@link GppCompilerPlugin} for core toolchain support.</p>
 *
 * <p>
 *     For each {@link NativeBinary} found, this plugin will:
 *     <ul>
 *         <li>Create a {@link CppCompile} task named "compile${binary-name}" to compile the C++ sources.</li>
 *         <li>Create a {@link LinkExecutable} or {@link LinkSharedLibrary} task named "link${binary-name}
 *             or a {@link AssembleStaticLibrary} task name "assemble${binary-name}" to create the binary artifact.</li>
 *         <li>Create an InstallTask named "install${Binary-name}" to install any {@link ExecutableBinary} artifact.
 *     </ul>
 * </p>
 */
@Incubating
class CppPlugin implements Plugin<ProjectInternal> {

    void apply(ProjectInternal project) {
        project.plugins.apply(BinariesPlugin)
        project.plugins.apply(MicrosoftVisualCppPlugin)
        project.plugins.apply(GppCompilerPlugin)
        project.extensions.create("cpp", CppExtension, project)

        // Defaults for all cpp source sets
        project.cpp.sourceSets.all { sourceSet ->
            sourceSet.source.srcDir "src/${sourceSet.name}/cpp"
            sourceSet.exportedHeaders.srcDir "src/${sourceSet.name}/headers"
        }

        project.binaries.withType(NativeBinary) { binary ->
            bindSourceSetLibsToBinary(binary)
            createTasks(project, binary)
        }
    }

    private static void bindSourceSetLibsToBinary(binary) {
        // TODO:DAZ Move this logic into NativeBinary (once we have laziness sorted)
        binary.source.withType(CppSourceSet).all { CppSourceSet sourceSet ->
            sourceSet.libs.each { NativeDependencySet lib ->
                binary.lib lib
            }
        }
    }

    def createTasks(ProjectInternal project, NativeBinaryInternal binary) {
        BinaryAssembleTask binaryAssembleTask
        if (binary instanceof StaticLibraryBinary) {
            binaryAssembleTask = createStaticLibraryTask(project, binary)
        } else {
            binaryAssembleTask = createLinkTask(project, binary)
        }
        binary.dependsOn binaryAssembleTask

        binary.source.withType(CppSourceSet).all { CppSourceSet sourceSet ->
            CppCompile compileTask = createCompileTask(project, binary, sourceSet)
            binaryAssembleTask.source compileTask.outputs.files.asFileTree.matching { include '**/*.obj', '**/*.o' }
        }

        if (binary instanceof ExecutableBinary) {
            createInstallTask(project, (NativeBinaryInternal) binary);
        }
    }


    private CppCompile createCompileTask(ProjectInternal project, NativeBinaryInternal binary, CppSourceSet sourceSet) {
        CppCompile compileTask = project.task(binary.namingScheme.getTaskName("compile", sourceSet.name), type: CppCompile) {
            description = "Compiles the $sourceSet sources of $binary"
        }

        compileTask.toolChain = binary.toolChain
        compileTask.forDynamicLinking = binary instanceof SharedLibraryBinary

        compileTask.includes sourceSet.exportedHeaders
        compileTask.source sourceSet.source
        binary.libs.each { NativeDependencySet deps ->
            compileTask.includes deps.includeRoots
        }

        compileTask.conventionMapping.objectFileDir = { project.file("${project.buildDir}/objectFiles/${binary.namingScheme.outputDirectoryBase}") }
        compileTask.conventionMapping.macros = { binary.macros }
        compileTask.conventionMapping.compilerArgs = { binary.compilerArgs }

        compileTask
    }

    private AbstractLinkTask createLinkTask(ProjectInternal project, NativeBinaryInternal binary) {
        AbstractLinkTask linkTask = project.task(binary.namingScheme.getTaskName("link"), type: linkTaskType(binary)) {
             description = "Links ${binary}"
             group = BasePlugin.BUILD_GROUP
         }

        linkTask.toolChain = binary.toolChain

        binary.libs.each { NativeDependencySet lib ->
            linkTask.lib lib.linkFiles
        }

        linkTask.conventionMapping.outputFile = { binary.outputFile }
        linkTask.conventionMapping.linkerArgs = { binary.linkerArgs }
        return linkTask
    }

    private static Class<? extends AbstractLinkTask> linkTaskType(NativeBinary binary) {
        if (binary instanceof SharedLibraryBinary) {
            return LinkSharedLibrary
        }
        return LinkExecutable
    }

    private AssembleStaticLibrary createStaticLibraryTask(ProjectInternal project, NativeBinaryInternal binary) {
        AssembleStaticLibrary task = project.task(binary.namingScheme.getTaskName("assemble"), type: AssembleStaticLibrary) {
             description = "Creates ${binary}"
             group = BasePlugin.BUILD_GROUP
         }

        task.toolChain = binary.toolChain
        task.conventionMapping.outputFile = { binary.outputFile }
        return task
    }

    def createInstallTask(ProjectInternal project, NativeBinaryInternal executable) {
        InstallExecutable installTask = project.task(executable.namingScheme.getTaskName("install"), type: InstallExecutable) {
            description = "Installs a development image of $executable"
            group = BasePlugin.BUILD_GROUP
        }

        installTask.conventionMapping.destinationDir = { project.file("${project.buildDir}/install/$executable.name") }

        installTask.conventionMapping.executable = { executable.outputFile }
        installTask.lib { executable.libs*.runtimeFiles }

        installTask.dependsOn(executable)
    }
}