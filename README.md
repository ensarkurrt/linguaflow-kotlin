# Android için LinguaFlow

Android 7+ Kotlin SDK; coroutine tabanlı remote delivery, `StateFlow` locale provider, atomik disk
cache, bundled asset fallback, tenant overlay, Play Integrity, Android ICU MessageFormat ve CLI ile
üretilen typed key'leri içerir.

```kotlin
val config = LinguaFlowConfig.fromAsset(context)
val client = LinguaFlowClient(
  context,
  config,
  integrityProvider = PlayIntegrityProvider(context),
)
lifecycleScope.launch {
  client.initialize()
  title.text = client.text(Lf.Home.greeting("Ada"))
  client.selectLocale("tr") // null cihaz dili çözümüne döner
}
```

`.linguaconfig` ile CLI'ın indirdiği `linguaflow/<locale>.json` dosyalarını `src/main/assets` altına
koyun. Views veya Compose tarafında `client.state` değerini `StateFlow` olarak izleyin. Kaynak
önceliği remote, release ile eşleşen atomik disk cache ve paketlenmiş asset şeklindedir. Branch
politikası gerektirmiyorsa Play Integrity opsiyoneldir.

Console'da proje Google bağlantısını ve app'in package name + Play App Signing SHA-256 digest
bilgisini kaydedin; sonra app'i project/branch integrity politikasına ekleyin. Google Cloud project
number SDK'ya tekrar girilmez: tek kullanımlık challenge response'uyla gelir. SDK bu `requestHash`i
Play Integrity Standard token'ına bağlar, sunucu Google'da decode edip app/account/device verdict'ini
doğrular ve branch başına kısa ömürlü delivery grant üretir.

Eksik anahtar raporlaması için `.linguaconfig` içindeki `telemetry.missingKeys` değerini ve branch
politikasını açın. SDK sürüm adını ve version code'u varsayılan olarak host uygulamanın
`PackageManager` bilgisinden okur; `telemetry.appVersion` yalnız test veya özel sürüm etiketi için
opsiyonel override'dır. SDK sinyalleri tekilleştirip partiler; telemetry hatası metin göstermeyi
durdurmaz. Uygulama kapanırken `client.close()` coroutine scope'unu temizler.

`PlayIntegrityProvider` yapılandırıldığında SDK ayrıca bundle indirme/parse, Delivery API ve ICU
sonuçlarını toplu runtime telemetrisi olarak gönderir. Yalnız bütünlük doğrulanmış raporlar otomatik
rollout sağlık kapılarında kullanılır.
