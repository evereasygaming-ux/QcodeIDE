plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.blamspot.jcode.nativeffi.vt"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments(
                    "-DQCODE_NATIVE_MODULE=vt",
                    "-DQCODE_JNI_OUTPUT_DIR=${project.buildDir}/intermediates/merged_native_libs",
                    "-DQCODE_VARIANT_DIR=debug",
                )
                cFlags += listOf("-std=c11", "-fvisibility=hidden")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("${rootProject.projectDir}/native/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DQCODE_NATIVE_MODULE=vt",
                        "-DQCODE_JNI_OUTPUT_DIR=${project.buildDir}/intermediates/merged_native_libs",
                        "-DQCODE_VARIANT_DIR=release",
                    )
                }
            }
        }
    }
}
