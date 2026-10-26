fn main() {
    println!("cargo:rustc-check-cfg=cfg(soong)");
    println!("cargo:rerun-if-changed=wrapper.h");
    println!("cargo:rerun-if-changed=../../third_party/rga/include");
    if std::env::var("CARGO_CFG_TARGET_OS").unwrap() != "android" {
        return;
    }
    let ndk = std::env::var("ANDROID_NDK_HOME").expect("ANDROID_NDK_HOME required");
    let sysroot = format!("{ndk}/toolchains/llvm/prebuilt/linux-x86_64/sysroot");
    bindgen::Builder::default().rust_target(bindgen::RustTarget::stable(80, 0).unwrap()).header("wrapper.h").clang_args([
        "--target=aarch64-linux-android31".to_owned(), format!("--sysroot={sysroot}"),
        format!("-I{sysroot}/usr/include/aarch64-linux-android"), "-I../../third_party/rga/include".to_owned()])
        .allowlist_function("(ATrace|AHardwareBuffer|ANativeWindow|egl|gl|imcheck|improcess|wrapbuffer|importbuffer|releasebuffer).*" )
        .allowlist_type("(rga_.*|im_.*|IM_.*|AHardwareBuffer.*|ANativeWindow.*|EGL.*|GL.*)")
        .allowlist_var("(RK_FORMAT_.*|IM_.*|EGL_.*|GL_.*|AHARDWAREBUFFER_.*)")
        .derive_default(true).layout_tests(false).generate_comments(false)
        .generate().expect("graphics bindings").write_to_file(
            std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("graphics.rs")).unwrap();
    for lib in ["android", "EGL", "GLESv2", "dl"] {
        println!("cargo:rustc-link-lib={lib}");
    }
}
