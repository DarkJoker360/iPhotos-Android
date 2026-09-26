# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# WorkManager persists worker class names in its database. Keep these classes
# and their Context/WorkerParameters constructors stable across app updates.
-keep class xyz.simoneesposito.ocloud.data.upload.PhotoUploadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class xyz.simoneesposito.ocloud.data.upload.CameraUploadScanWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class xyz.simoneesposito.ocloud.data.download.PhotoDownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class xyz.simoneesposito.ocloud.data.sync.LibrarySyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
