(ns triangle.webgpu-test
  "Layout checks for the struct builders. These need no GPU and no
  window: they only inspect the byte layout of the descriptor
  buffers, so \"lein test\" runs them anywhere the JDK runs."
  (:require [clojure.test :refer [deftest is testing]]
            [triangle.ffi :as ffi]
            [triangle.wgpu :as wgpu]))

(deftest test-platform-descriptor!
  (testing "the per-platform surface chain struct"
    (let [descriptor (wgpu/platform-descriptor! :x11 [0x1000 0x2000])]
      (is (= 0x3 (ffi/deref-u32 descriptor 8)))
      (is (= 0x1000 (ffi/deref-pointer descriptor 16)))
      (is (= 0x2000 (ffi/deref-pointer descriptor 24)))))
  (testing "Cocoa carries one member: the CAMetalLayer"
    (let [descriptor (wgpu/platform-descriptor! :cocoa [0x1000])]
      (is (= 0x1 (ffi/deref-u32 descriptor 8)))
      (is (= 0x1000 (ffi/deref-pointer descriptor 16))))))

(deftest test-surface-configuration!
  (testing "the surface configuration struct (56 bytes, v22 layout)"
    (let [configuration (wgpu/surface-configuration! 0x1122334455667788 800 600)]
      (is (= 0x1122334455667788 (ffi/deref-pointer configuration 8)))
      (is (= 0x18 (ffi/deref-u32 configuration 16)))
      (is (= 0x11 (ffi/deref-u32 configuration 20)))
      (is (= 800 (ffi/deref-u32 configuration 44)))
      (is (= 600 (ffi/deref-u32 configuration 48)))
      (is (= 0 (ffi/deref-u32 configuration 52))))))

(deftest test-color-target-state!
  (testing "the colour target struct (format at 8, writeMask at 24)")
  (let [target (wgpu/color-target-state!)]
    (is (= 0x18 (ffi/deref-u32 target 8)))
    (is (= 0xF (ffi/deref-u32 target 24)))))

(deftest test-render-pipeline-descriptor!
  (testing "the flat 144-byte pipeline descriptor (states embedded by value)"
    (let [shader (ffi/allocate! 32)
          fragment (wgpu/fragment-state! shader (wgpu/color-target-state!))
          pipeline (wgpu/render-pipeline-descriptor! shader fragment)]
      (is (some? (ffi/deref-pointer pipeline 32)))    ; vertex.module
      (is (some? (ffi/deref-pointer pipeline 40)))    ; vertex.entryPoint
      (is (some? (ffi/deref-pointer pipeline 136)))   ; fragment
      (is (= 3 (ffi/deref-u32 pipeline 88)))          ; primitive.topology
      (is (= 1 (ffi/deref-u32 pipeline 120)))         ; multisample.count
      ;; 0xFFFFFFFF does not fit an int; stored as a Java int this reads
      ;; back as -1, which is the same 32 bits.
      (is (= -1 (ffi/deref-u32 pipeline 124))))))    ; multisample.mask
