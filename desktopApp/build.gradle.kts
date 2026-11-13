import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.jna.core)
    implementation(libs.jna.platform)
    implementation(libs.jlayer)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutinesTest)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.example.connectallspeakers.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "com.example.connectallspeakers"
            packageVersion = "1.0.0"
        }
    }
}

// Bundle the ordinary user-mode activation DLL for JNA, including packaged MSI/app images.
val processCaptureResources = layout.buildDirectory.dir("generated/processCaptureResources")
val buildProcessCapture by tasks.registering(Exec::class) {
    onlyIf { System.getProperty("os.name").startsWith("Windows") }
    inputs.files(rootProject.fileTree("native/windows/processCapture") { exclude("build/**") })
    outputs.dir(processCaptureResources)
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        rootProject.file("native/windows/processCapture/Build.ps1").absolutePath,
        "-ResourceDirectory", processCaptureResources.get().asFile.absolutePath)
}
sourceSets.main { resources.srcDir(processCaptureResources) }
tasks.named("processResources") { dependsOn(buildProcessCapture) }
