fn main() {
    println!("cargo:rustc-check-cfg=cfg(soong)");
    println!("cargo:rerun-if-changed=wrapper.h");
    let mut builder = bindgen::Builder::default()
        .rust_target(bindgen::RustTarget::stable(80, 0).unwrap())
        .header("wrapper.h")
        .allowlist_type("v4l2_.*")
        .allowlist_var("V4L2_.*")
        .derive_default(true)
        .layout_tests(false)
        .generate_comments(false);
    if std::env::var("CARGO_CFG_TARGET_OS").unwrap() == "android" {
        let ndk = std::env::var("ANDROID_NDK_HOME").expect("ANDROID_NDK_HOME required");
        let sysroot = format!("{ndk}/toolchains/llvm/prebuilt/linux-x86_64/sysroot");
        builder = builder.clang_args([
            "--target=aarch64-linux-android31".to_owned(),
            format!("--sysroot={sysroot}"),
            format!("-I{sysroot}/usr/include/aarch64-linux-android"),
        ]);
    }
    builder
        .generate()
        .expect("V4L2 bindings")
        .write_to_file(std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("v4l2.rs"))
        .unwrap();
}
