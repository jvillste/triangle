(defproject triangle "0.1.0-SNAPSHOT"
  :description "Colored triangle in a window, drawn via WebGPU (wgpu-native) from Clojure"
  :dependencies [[org.clojure/clojure "1.11.4"]
                 [org.lwjgl/lwjgl "3.3.6"]
                 [org.lwjgl/lwjgl-glfw "3.3.6"]
                 ;; LWJGL native libraries (auto-extracted to a temp directory at runtime)
                 [org.lwjgl/lwjgl "3.3.6" :classifier "natives-linux"]
                 [org.lwjgl/lwjgl "3.3.6" :classifier "natives-linux-arm64"]
                 [org.lwjgl/lwjgl "3.3.6" :classifier "natives-macos"]
                 [org.lwjgl/lwjgl "3.3.6" :classifier "natives-macos-arm64"]
                 [org.lwjgl/lwjgl "3.3.6" :classifier "natives-windows-x86"]
                 [org.lwjgl/lwjgl-glfw "3.3.6" :classifier "natives-linux"]
                 [org.lwjgl/lwjgl-glfw "3.3.6" :classifier "natives-linux-arm64"]
                 [org.lwjgl/lwjgl-glfw "3.3.6" :classifier "natives-macos"]
                 [org.lwjgl/lwjgl-glfw "3.3.6" :classifier "natives-macos-arm64"]
                 [org.lwjgl/lwjgl-glfw "3.3.6" :classifier "natives-windows-x86"]]
  :java-source-paths ["java"]
  :java-opts ["--release" "25"]
  :resource-paths ["resources"]
  :test-paths ["src" "test"]
  ;; -XstartOnFirstThread only exists on macOS JVMs (Cocoa needs AppKit on
  ;; the first thread). The Linux HotSpot refuses to even start when it is
  ;; passed, so it is added only when the host is a Mac.
  :jvm-opts ~(if (re-find #"(?i)mac|darwin" (System/getProperty "os.name"))
              '["--enable-native-access=ALL-UNNAMED" "-Dorg.lwjgl.util.NoChecks=true" "-XstartOnFirstThread"]
              ["--enable-native-access=ALL-UNNAMED" "-Dorg.lwjgl.util.NoChecks=true"])
  :main triangle.core)
