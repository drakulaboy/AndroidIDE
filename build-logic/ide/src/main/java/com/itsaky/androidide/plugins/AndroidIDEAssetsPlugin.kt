/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.plugins

import BuildConfig
import FDroidConfig
import VersionUtils
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.BaseExtension
import com.itsaky.androidide.plugins.conf.flavorsAbis
import com.itsaky.androidide.plugins.tasks.AddAndroidJarToAssetsTask
import com.itsaky.androidide.plugins.tasks.AddFileToAssetsTask
import com.itsaky.androidide.plugins.tasks.DownloadAapt2Task
import com.itsaky.androidide.plugins.tasks.GenerateInitScriptTask
import com.itsaky.androidide.plugins.tasks.GradleWrapperBuilderTask
import downloadVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.configurationcache.extensions.capitalized
import java.io.File

/**
 * Handles asset copying and generation.
 *
 * @author Akash Yadav
 */
class AndroidIDEAssetsPlugin : Plugin<Project> {

  companion object {

    private val AAPT2_CHECKSUMS = mapOf(
      "arm64-v8a" to "be2cea61814678f7a9e61bf818a6666e6097a7d67d6c19498a4d7aa690bc4151",
      "armeabi-v7a" to "ba3413c680933dffd3c3d35da8d450c474ff5ccab95c4b9db28841c53b7a3cdf",
      "x86_64" to "4861171c1efcffe41f4466937e6a392b243ffb014813b4e60f0b77bb46ab254d"
    )
  }

  override fun apply(target: Project) {
    target.run {
      val wrapperBuilderTaskProvider = tasks.register("generateGradleWrapper",
        GradleWrapperBuilderTask::class.java
      )

      val androidComponentsExtension = extensions.getByType(AndroidComponentsExtension::class.java)
      val baseExtension = extensions.getByType(BaseExtension::class.java)

      val aapt2Tasks = getAapt2DownloadTasks(baseExtension)

      val addAndroidJarTaskProvider = tasks.register("addAndroidJarToAssets",
        AddAndroidJarToAssetsTask::class.java) {
        sdkDirectory = androidComponentsExtension.sdkComponents.sdkDirectory.get().asFile
      }

      androidComponentsExtension.onVariants { variant ->

        if (aapt2Tasks.isNotEmpty()) {
          val downloadAapt2TaskProvider = aapt2Tasks[variant.flavorName]
            ?: throw IllegalStateException(
              "'aapt2' task not registered for flavor '${variant.flavorName}'")

          variant.sources.assets?.addGeneratedSourceDirectory(downloadAapt2TaskProvider,
            DownloadAapt2Task::outputDirectory)
        } else {
          variant.sources.assets?.addStaticSourceDirectory(
            getStaticFDroidAapt2Dir(variant.flavorName!!))
        }

        val variantNameCapitalized = variant.name.capitalized()

        variant.sources.assets?.addGeneratedSourceDirectory(wrapperBuilderTaskProvider,
          GradleWrapperBuilderTask::outputDirectory)

        variant.sources.assets?.addGeneratedSourceDirectory(addAndroidJarTaskProvider,
          AddAndroidJarToAssetsTask::outputDirectory)

        // Init script generator
        val generateInitScript = tasks.register(
          "generate${variantNameCapitalized}InitScript",
          GenerateInitScriptTask::class.java
        ) {
          mavenGroupId.set(BuildConfig.packageName)
          snapshotsRepository.set(VersionUtils.SNAPSHOTS_REPO)
          downloadVersion.set(this@run.downloadVersion)
        }

        variant.sources.assets?.addGeneratedSourceDirectory(
          generateInitScript,
          GenerateInitScriptTask::outputDir
        )

        // Tooling API JAR copier
        val copyToolingApiJar = tasks.register(
          "copy${variantNameCapitalized}ToolingApiJar",
          AddFileToAssetsTask::class.java
        ) {
          val toolingApi = rootProject.findProject(":subprojects:tooling-api-impl")!!
          dependsOn(toolingApi.tasks.getByName("copyJar"))

          val toolingApiJar = toolingApi.layout.buildDirectory.file(
            "libs/tooling-api-all.jar")

          inputFile.set(toolingApiJar)
          baseAssetsPath.set("data/common")
        }

        variant.sources.assets?.addGeneratedSourceDirectory(
          copyToolingApiJar,
          AddFileToAssetsTask::outputDirectory
        )
      }
    }
  }

  private fun Project.getStaticFDroidAapt2Dir(flavorName: String): String {
    val fdroidArch = FDroidConfig.fDroidBuildArch!!
    val fdroidAapt2 = FDroidConfig.fDroidAapt2File!!

    require(fdroidArch in flavorsAbis.keys) {
      "F-Droid arch '${fdroidArch}' is not supported!"
    }


    val inFile = File(fdroidAapt2)
    require(inFile.exists() && inFile.isFile) {
      "F-Droid AAPT2 file does not exist or is not a file: $inFile"
    }

    val assetDir = project.layout.buildDirectory.dir("intermediates/fdroid-aapt2-${flavorName}")
      .get().asFile

    if (assetDir.exists()) {
      assetDir.deleteRecursively()
    }

    assetDir.mkdirs()

    val outFile = File(assetDir, "data/$flavorName/aapt2")
    outFile.parentFile.mkdirs()

    inFile.copyTo(outFile, overwrite = true)

    return assetDir.absolutePath
  }

  private fun Project.getAapt2DownloadTasks(
    baseExtension: BaseExtension
  ): Map<String, TaskProvider<DownloadAapt2Task>> {

    if (FDroidConfig.isFDroidBuild) {
      // Do not download AAPT2 when building for F-Droid
      return emptyMap()
    }

    val aapt2Tasks = mutableMapOf<String, TaskProvider<DownloadAapt2Task>>()

    baseExtension.productFlavors.forEach { flavor ->

      val task = tasks.register(
        "downloadAapt2${flavor.name.capitalized()}",
        DownloadAapt2Task::class.java
      ) {
        arch.set(flavor.name)
        checksum.set(AAPT2_CHECKSUMS[flavor.name] ?: throw IllegalStateException(
          "Checksum for aapt2-${flavor.name} not found!"))
      }

      aapt2Tasks[flavor.name] = task
    }

    return aapt2Tasks
  }
}

