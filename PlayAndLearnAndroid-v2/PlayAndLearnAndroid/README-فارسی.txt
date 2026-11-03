Play and Learn English - پروژه Android Studio (نسخه ۲)

ساخت APK
1) Android Studio را نصب کن و همین پوشه (PlayAndLearnAndroid) را Open کن. بار اول اینترنت لازم است.
2) صبر کن Gradle Sync تمام شود.
3) منوی Build > Build APK(s). فایل app-debug.apk ساخته می‌شود.
(راه دیگر: این پوشه را در یک repository خصوصی GitHub بگذار و workflow به نام Build APK را از تب Actions اجرا کن.)

چیزهایی که می‌توانی عوض کنی
- نام برنامه: app/src/main/res/values/strings.xml
- آیکون: فایل‌های mipmap-* داخل app/src/main/res (از لوگوی خودت ساخته شده)
- فیلم ابتدایی: app/src/main/res/raw/intro.mp4 (اسم فایل باید همین بماند، فقط انگلیسی کوچک)
- رد کردن فیلم با لمس: در MainActivity.java مقدار ALLOW_SKIP_INTRO را true کن
- بازی: app/src/main/assets/index.html
- نسخه جدید: versionCode و versionName در app/build.gradle (هر انتشار جدید یک عدد بیشتر)
نکته: برای نصب روی نسخه قبلی، هر بار با همان روش/همان کامپیوتر بساز (امضای APK یکی باشد).
