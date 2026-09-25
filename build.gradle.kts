plugins {
    alias(libs.plugins.fabric.loom)
}

val modArchivesName = providers.gradleProperty("archives_base_name").get()
val mavenGroup = providers.gradleProperty("maven_group").get()

// ---------------------------------------------------------------------------
// 多版本构建
//   默认                          -> 1.21.4,产物 hongshikaikai-0.1.0.jar(和以前完全一样)
//   ./gradlew build -Pmc=1.21.8   -> 产物 hongshikaikai-1.21.8-0.1.0.jar
// ---------------------------------------------------------------------------
data class Target(val yarn: String, val loader: String, val jdk: Int, val splash: String, val compat: String)

private val DEFAULT_MC = "1.21.4"

// yarn 版本取自 https://meta.fabricmc.net/v2/versions/yarn/<mc>
//
// splash / compat 是两个**互相独立**的世代维度,各自对应 src/families/ 下的一个目录:
//
//   splash-legacy : 1.21.5 及更早。SplashOverlay 调 drawTexture(Function<Identifier,
//                   RenderLayer>, Identifier, IIFFIIIIIII),2D 矩阵栈是 MatrixStack。
//   splash-modern : 1.21.6 起。同一个调用换成 RenderPipeline 参数,矩阵栈换成 Matrix3x2fStack。
//
//   compat-legacy : 1.21.4 及更早。Entity.prevYaw/prevPitch 是 public 字段,
//                   PlayerInventory.selectedSlot 也是 public 字段。
//   compat-modern : 1.21.5 起。prevYaw/prevPitch 改名 lastYaw/lastPitch,
//                   selectedSlot 变私有、改走 getSelectedSlot()/setSelectedSlot()。
//
// 两个维度的分界线不一样(1.21.6 vs 1.21.5),所以不能合成一个"版本族"。以上名字都是拿
// javap 在每版 Loom 产出的命名 jar 上逐版核对出来的,不是猜的。
private val targets: Map<String, Target> = mapOf(
    "1.21.4" to Target("1.21.4+build.1", "0.16.10", 21, "legacy", "legacy"),
    "1.21.5" to Target("1.21.5+build.1", "0.16.10", 21, "legacy", "modern"),
    "1.21.6" to Target("1.21.6+build.1", "0.16.10", 21, "modern", "modern"),
    "1.21.7" to Target("1.21.7+build.8", "0.16.10", 21, "modern", "modern"),
    "1.21.8" to Target("1.21.8+build.1", "0.16.10", 21, "modern", "modern"),
    "1.21.10" to Target("1.21.10+build.3", "0.19.3", 21, "modern", "modern"),
    "1.21.11" to Target("1.21.11+build.6", "0.19.3", 21, "modern", "modern"),
)

val requestedMc = (findProperty("mc") as String?)?.trim().orEmpty()
val mcVersion = requestedMc.ifEmpty { DEFAULT_MC }
val target = targets[mcVersion]
    ?: throw GradleException("不支持的 -Pmc=$mcVersion,可用:${targets.keys.sorted().joinToString(", ")}")
val isExplicitTarget = requestedMc.isNotEmpty()

base {
    archivesName.set(if (isExplicitTarget) "$modArchivesName-$mcVersion" else modArchivesName)
    version = libs.versions.mod.version.get()
    group = mavenGroup
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    // Fabric
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings("net.fabricmc:yarn:${target.yarn}:v2")
    implementation("net.fabricmc:fabric-loader:${target.loader}")

    // Meteor Client(版本号 = Minecraft 版本)
    modImplementation("meteordevelopment:meteor-client:$mcVersion-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(target.jdk))
    }
}

// 世代专用源码:同一份主源码 + 每个世代维度各自的一份实现。
sourceSets {
    named("main") {
        java.srcDir("src/families/splash-${target.splash}/java")
        java.srcDir("src/families/compat-${target.compat}/java")
    }
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "minecraft_version" to mcVersion,
            "jdk_version" to target.jdk.toString(),
        )

        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", modArchivesName)

        from("LICENSE") {
            rename { "${it}_$modArchivesName" }
        }
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:deprecation",
                "-Xlint:unchecked"
            )
        )
    }
}
