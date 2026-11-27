# R8 rules for Deskflow Android (code review D23). Only what reflection or IPC needs is kept.

# Kotlin metadata: kotlin-reflect needs it for KClass.createInstance() below.
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations

# Protocol messages are created via reflection (MessageParser: template.clazz.createInstance()).
-keep class org.tfv.deskflow.client.io.msgs.** { *; }

# Events go through Bundle.putSerializable and are matched by class name (ConnectionServiceClient).
-keep class org.tfv.deskflow.client.events.** { *; }
-keep class org.tfv.deskflow.logging.LogRecordEvent { *; }
-keep class org.tfv.deskflow.client.models.ClipboardData { *; }
-keep class org.tfv.deskflow.client.models.ClipboardData$** { *; }
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Looked up with Class.forName (KLoggingManager).
-keep class org.tfv.deskflow.logging.AndroidForwardingLogger { *; }

# AIDL parcelables/interfaces and DataStore protobuf models (protobuf-java uses reflection).
-keep class org.tfv.deskflow.data.aidl.** { *; }
-keep class org.tfv.deskflow.data.models.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageV3 { *; }
-keep class * extends com.google.protobuf.GeneratedMessage { *; }

# Logging backends that are optional at runtime.
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn org.apache.log4j.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
