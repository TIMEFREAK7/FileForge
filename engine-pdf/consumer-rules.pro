# PDFBox-Android: optional JPEG 2000 support (com.gemalto.jp2) is not bundled; JPX images report an error instead.
-dontwarn com.gemalto.jp2.**
# Bouncy Castle is excluded (certificate-encrypted PDFs are not supported).
-dontwarn org.bouncycastle.**
