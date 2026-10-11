# AppImage packaging

`packageAppImage` uses appimagetool 1.9.1 from the maintained AppImage project.
Download the x86_64 asset from the [1.9.1 release](https://github.com/AppImage/appimagetool/releases/tag/1.9.1)
and verify its GitHub release asset digest before placing it at
`build/tools/appimagetool-1.9.1-x86_64.AppImage`. The build deliberately
does not download or execute packaging tools during Gradle configuration.

The staging task creates the host application image before assembling the
AppDir. It does not require Debian or RPM packaging tools. Linux packaging
evidence belongs to validation.
