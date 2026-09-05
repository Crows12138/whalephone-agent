set JAVA_HOME=C:\wp\tools\jdk17
set ANDROID_HOME=C:\wp\tools\android-sdk
set ANDROID_SDK_ROOT=C:\wp\tools\android-sdk
echo no | C:\wp\tools\android-sdk\cmdline-tools\latest\bin\avdmanager.bat create avd -n wp16 -k "system-images;android-36-ext19;google_apis;x86_64" -d pixel_6 --force
