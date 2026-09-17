// The /tmp tmpfs is under a per-user quota that our unit tests exhaust while
// Robolectric extracts its native runtime. Build outputs therefore live on
// /home, which has plenty of space; sources stay in the worktree.
allprojects {
    layout.buildDirectory.set(File("/home/stealth/wt-v16-build/" + project.name))
}
