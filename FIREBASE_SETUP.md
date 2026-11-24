# Firebase Analytics — راه‌اندازی

این پروژه برای آمار ناشناس استفاده (کاربر فعال، صفحه‌ها، دانلود) از Firebase Analytics استفاده می‌کند.
اپ بدون `app/google-services.json` هم build می‌شود، ولی آمار غیرفعال می‌ماند. برای فعال شدن آمار، فایل را طبق مراحل زیر اضافه کن.

## ۱) ساخت پروژه Firebase

1. برو به <https://console.firebase.google.com/>
2. **Add project** → نام: `KurdTube` → ادامه.
3. Google Analytics را فعال کن (برای داشبورد آمار لازم است).

## ۲) افزودن اپ اندروید

1. در صفحه‌ی پروژه، روی آیکن اندروید (**Add app**) بزن.
2. **Android package name** را دقیقاً این بگذار:
   ```
   com.techno.kurdtube
   ```
3. یک نام دلخواه (مثلاً `KurdTube Android`) بده. SHA-1 لازم نیست (برای Analytics نیازی نیست).
4. **Register app**.

## ۳) دانلود google-services.json

1. فایل `google-services.json` را دانلود کن.
2. آن را دقیقاً اینجا بگذار:
   ```
   KurdTube/app/google-services.json
   ```
   (کنار `app/build.gradle`)

> این فایل در `.gitignore` است و کامیت نمی‌شود. برای هر کسی که پروژه را build می‌کند باید جداگانه اضافه شود.

### استفاده در GitHub Actions (اختیاری)

اگر می‌خواهی APKهایی که در CI ساخته می‌شوند هم آمار Firebase داشته باشند، محتوای کامل `google-services.json` را در یک Secret به نام `GOOGLE_SERVICES_JSON` ذخیره کن. Workflowها در صورت وجود این Secret فایل را قبل از build می‌سازند؛ در غیر این صورت بدون Firebase build می‌شوند.

## ۴) Sync و Build

1. فایل `google-services.json` را در `app/` بگذار (مرحله‌ی قبل).
2. در Android Studio: **File → Sync Project with Gradle Files** تا پلاگین Google Services فعال شود.
3. **Build → Clean Project** و بعد **Build → Build Bundle(s)/APK(s) → Build APK(s)**
4. نسخه باید `1.2` (versionCode `120`) باشد.

## ۵) دیدن آمار

در Firebase Console:

- **Analytics → Dashboard**: کاربران فعال، اولین باز شدن‌ها، جلسات، نگه‌داشت (Retention)
- **Analytics → Realtime**: کاربران آنلاین در همان لحظه
- **Analytics → Events**: رویدادها (`app_start`، `screen_view`، `song_play`، ...)
- **Analytics → Users**: کشور، زبان، نسخه‌ی اپ، مدل دستگاه

> داده‌های اصلی معمولاً تا ۲۴ ساعت بعد در Dashboard ظاهر می‌شوند؛ Realtime تقریباً فوری است.

## ۶) تست با DebugView

برای دیدن رویدادها در لحظه:

```bash
adb shell setprop debug.firebase.analytics.app com.techno.kurdtube
```

بعد اپ را باز کن و در Firebase Console → **Analytics → DebugView** رویدادها را ببین.
برای خاموش کردن:

```bash
adb shell setprop debug.firebase.analytics.app .none.
```

## رویدادهایی که ثبت می‌شوند

| رویداد | پارامترها | توضیح |
|---|---|---|
| `app_start` | `app_version`, `app_language` | هر بار باز شدن Activity |
| `screen_view` | `screen_name` = SEARCH/LIBRARY/PLAYER/SETTINGS | تغییر صفحه |
| `song_play` | `shuffle`, `queue_size` | شروع پخش |
| `download_started` | — | شروع دانلود |
| `download_completed` | — | دانلود موفق |
| `playlist_created` | — | ساخت پلی‌لیست |
| `language_changed` | `language` | تغییر زبان |

رویدادهای خودکار Firebase مثل `first_open`، `session_start` و `user_engagement` هم ثبت می‌شوند.

## حریم خصوصی

- هیچ عنوان آهنگ، عبارت جستجو، مسیر فایل یا اطلاعات شخصی ارسال نمی‌شود.
- جمع‌آوری **Advertising ID** در `AndroidManifest.xml` غیرفعال شده است.
- برای انتشار عمومی، یک **Privacy Policy** در اپ/سایت قرار بده و ذکر کن که آمار ناشناس استفاده جمع‌آوری می‌شود.

## عیب‌یابی

| مشکل | راه‌حل |
|---|---|
| اپ build می‌شود ولی آماری ثبت نمی‌شود | `app/google-services.json` را اضافه کن، Sync و Clean/Build کن؛ در Logcat عبارت `Firebase Analytics disabled` را چک کن. |
| Sync خطای package name | مطمئن شو در Firebase پکیج `com.techno.kurdtube` ثبت شده. |
| هیچ داده‌ای نمی‌آید | ۲۴ ساعت صبر کن؛ Realtime و DebugView را چک کن؛ اینترنت دستگاه را بررسی کن. |
| کاربران کم‌تر از انتظار | Firebase روی دستگاه‌های بدون Google Play Services ممکن است داده نفرستد. |
