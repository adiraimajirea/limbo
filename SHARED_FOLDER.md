# Live shared folder (WebDAV)

Limbo can share an Android folder with the guest OS **live**: changes made on Android or in the
guest are visible on the other side immediately. This does not use qemu's vvfat (disabled, buggy)
or virtfs/9p (disabled in this build), so no native rebuild is needed.

How it works: while the VM runs, Limbo starts a small WebDAV server bound to the device's
loopback (127.0.0.1). With the **User** network (slirp) the guest reaches the device loopback at
`10.0.2.2`, so the share is `http://10.0.2.2:8090/` (port is configurable).

## Use
1. Machine -> Shared Folder: pick a local directory.
2. Machine network must be **User**.
3. Start the VM. A toast shows the address.
4. Settings -> Shared Folder: toggle the server, change the port, read-only mode, optional password.

## In the guest
- **Windows**: Explorer -> Map network drive -> `http://10.0.2.2:8090/` (WebClient service must be
  running). With a password, use any user name. Plain-HTTP Basic auth may need
  `HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters\BasicAuthLevel = 2`.
- **Linux**: `sudo mount -t davfs http://10.0.2.2:8090/ /mnt/share` (package `davfs2`), or any file
  manager that supports `dav://10.0.2.2:8090/`.
- **Anything**: a browser at `http://10.0.2.2:8090/` shows a plain directory listing.

## Notes / limits
- Only local directories are supported (the Shared Folder picker uses the legacy file manager).
- Without a password, any app on the device can reach the port while the VM is running.
- File locking is not enforced (LOCK is answered only so Windows will write).
