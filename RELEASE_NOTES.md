# OverDrive Bulut — Sürüm Notları

BYD Tang ve Sealion 7 (DiLink 5, 51.1.x) için hazırlanan OverDrive sürümlerinin notları.

## 57.0.17 — 2026-11-06

- Telefon kaydı (OverDrive Bulut eşleştirmesi) düzeltildi: kontrol paneli artık süresi dolmuş kodu göstermiyor. Araç açılıp bulut bağlanır bağlanmaz bölüm açıldığında yeni kod istenmiyor ve eski kod okutulunca "Bu koda ait araç bulunamadı" hatası alınıyordu; şimdi QR her görünür olduğunda yeni kod alınır, gelene kadar "Yeni eşleştirme kodu alınıyor…" yazar, açık kaldıkça 10 dakikada bir yenilenir
- Kontrol panelinde kod, "Eşleştirme kodu" başlığıyla büyük ve "15 dakika geçerli, tek kullanımlık" notuyla görünür; "Erişim Kodu" artık "Web giriş kodu" (OverDrive Bulut dışından web arayüzüne girişte sorulur, telefona araç eklemek için değil), düğmesi "Giriş kodunu yenile"; bölüm başlığı "Uzaktan erişim (Telefon Kaydı)"
- Açılışta "OverDrive hazırlanıyor" bandı: araç ya da uygulama açıldığında Kamera, Gözetim, ACC Gözetim ve OverDrive Bulut servisleri çalışana kadar her ekranın üstünde ilerleme gösterilir; hepsi çalışınca "OverDrive hazır ✓" deyip 10 saniye sonra kapanır

## 57.0.16 — 2026-11-06

- OverDrive Bulut: araç, Hakkında ekranındaki sistem bilgilerini (yazılım, DSP, MCU, Android sürümü, güvenlik yaması, ünite) de buluta bildirir; VIN gönderilmez

## 57.0.15 — 2026-11-06

- OverDrive Bulut: araç, Ayarlar'da seçili araç modelini (seçilmemişse boş) uygulama sürümüyle birlikte buluta bildirir

## 57.0.14 — 2026-11-06

- OverDrive Bulut eşleştirme kodu artık 15 dakika geçerli ve tek kullanımlık: ekranın fotoğrafı ya da eski bir ekran görüntüsüyle sonradan hesap eklenemez. Araçtaki OverDrive Bulut penceresi ve ana paneldeki uzaktan erişim bölümü her açıldığında yeni kod alır; başka bir hesap eklemek için pencereyi yeniden açmanız yeterli
- Araca yeni bir hesap eklendiğinde, o araca zaten bağlı hesapların telefonlarına bildirim gider

## 57.0.13 — 2026-11-06

- OverDrive Bulut tek tünel: eski "kendi frps sunucun" modu (elle sunucu ve token girilen ayar ekranı, "Kendi sunucum" düğmesi) kaldırıldı. Bu ayarı taşıyan bir araç güncellemeden sonra ayarı siler ve OverDrive Bulut'a kendiliğinden kaydolur; FRP kartı kayıt yoksa kaydı hemen başlatır

## 57.0.12 — 2026-11-05

- Mağaza kurulumları (Play Mağazası ve Uygulama Mağazası): Chrome, Angry Birds 2 gibi büyük uygulamaların "kuruluyor"da takılı kalması düzeltildi. APK'lar ADB bağlantısı üzerinden akıtılırken araçta zaman zaman yarıda durup bir daha devam etmiyordu; artık Android dosyaları doğrudan diskten okuyor, aktarım saniyeler sürüyor. Yarım kalan kurulum geride oturum bırakmıyor

## 57.0.11 — 2026-11-04

- Sealion 7 / Tang: araç modeli olarak Sealion 7 ya da Tang seçiliyse Kilidi aç, Far yak (selektör) ve Bagajı aç düğmeleri pasif görünür; bu araçlar bu komutları uygulamıyor. Kilitle ve Bagajı kapat çalışmaya devam eder. Ana paneldeki Hızlı kontroller, Araç kontrol sayfası, Canlı görüntüdeki Far düğmesi ve araç ekranındaki kısayol paneli (araç kilitliyken kilit anahtarı, Bagaj ve Far) için geçerli; telefondan açılan arayüz de aynıdır

## 57.0.10 — 2026-11-03

- OverDrive Bulut: araç kapatıldıktan sonra (uyanık tut açıkken) telefonda "çevrimdışı" görünme sorunu düzeltildi. Araç kapanınca tünel, sunucu adresini çözemediği için yeniden bağlanamıyordu; artık adres çözümü araç açık da kapalı da olsa çalışıyor

## 57.0.9 — 2026-11-03

- Güncelleme mobil veride de iniyor: bağlantı koparsa ya da takılırsa indirme baştan değil kaldığı yerden devam eder; veri aktığı sürece sabit 10 dakikalık süre sınırına takılıp düşmez (en fazla 60 dk, 4 dk hiç veri gelmezse vazgeçer)
- Bu iyileştirme 57.0.9'dan sonraki güncellemelerde geçerlidir; 57.0.9'un kendisi eski indiriciyle iner (mümkünse Wi-Fi ile güncelleyin)

## 57.0.8 — 2026-11-03

- Play Mağazası: sol menüde Uygulama Mağazası'nın altında. İlk basışta OverDrive Play Mağaza'yı (Google Play istemcisi, Aurora Store çatalı) kendiliğinden indirip kurar; Google Play'deki uygulamalar, parçalı APK'lar dahil, onay ekranı çıkmadan OverDrive üzerinden kurulur, güncellenir ve kaldırılır
- Uygulama Mağazası (F-Droid) olduğu gibi çalışmaya devam eder; iki mağaza da yeni sürümleri çıkınca açılmadan önce kendiliğinden güncellenir
- Google Play Hizmetleri gerektiren uygulamalar (ör. resmî YouTube), ünitede bu hizmetler yoksa kurulsa da çalışmayabilir

## 57.0.7 — 2026-11-02

- Uygulama Mağazası düzeltmesi: mağaza ve mağazadan kurulan uygulamalar artık `adb install` gibi doğrudan paket yöneticisine aktarılarak kuruluyor; 57.0.6'da araç dosya aktarımını reddettiği için kurulum yapılamıyordu

## 57.0.6 — 2026-11-02

- Uygulama Mağazası: sol menünün en üstünde. İlk basışta OverDrive Mağaza'yı (F-Droid istemcisi) kendiliğinden indirip kurar; F-Droid ve IzzyOnDroid'deki uygulamalar onay ekranı çıkmadan, OverDrive güncellemesi gibi kurulur, güncellenir ve kaldırılır
- Mağaza ayar gerektirmez: uygulama listesi mobil veride de yenilenir, kurulan APK'lar silinir; güncellemeler bildirimle gelir, kullanıcı tek dokunuşla kurar (isterse otomatik güncellemeyi açabilir)
- Mağazanın yeni sürümü çıktığında, mağaza açılmadan önce kendiliğinden güncellenir

## 57.0.5 — 2026-10-27

- Tang / Sealion 7: telefondan ya da ekrandan kilitleme artık çalışıyor. Aracın kabin servisi sürücü kapısı kilidini okuyamadığı için komut hiç gönderilmiyordu; artık kilit durumu car_service'ten okunuyor ve kapının gerçekten kilitlendiği oradan teyit ediliyor. Araç kilitliyken kilit açma komutunu araç kabul etmiyor; uygulama bunu "başarısız" olarak bildirir

## 57.0.4 — 2026-10-26

- Kayıt indirme uygulamanın içinde: alttaki panelde yüzde ve MB olarak ilerleme, İptal; indirilen dosyaya tekrar basınca ikinci indirme başlamaz
- iPhone (ana ekran uygulaması): indirme bitince "Kaydet" ile paylaşım menüsü açılır (Videoyu Kaydet / Dosyalara Kaydet); tam ekran önizlemeye takılıp uygulamadan çıkılmaz
- Kanıt paketi de aynı şekilde indirilir

## 57.0.3 — 2026-10-26

- Aracın web arayüzündeki "Güncelleme var" düğmesi köşedeki düğmeyle çakışmaz ve arayüzün dilinde görünür

## 57.0.2 — 2026-10-26

- Araçlarım'da her aracın OverDrive sürümü görünür; yeni sürüm varsa "Güncelleme var" düğmesiyle telefondan, araçtan uzaktayken bile güncellenir
- Araç, uygulama sürümünü OverDrive Bulut'a kendiliğinden bildirir
- Aracın web arayüzünde bekleyen güncelleme her sayfada sağ üstte görünür, saatlik kontrol

## 57.0.1 — 2026-10-26

- OverDrive Bulut: araç kurulunca buluta kendiliğinden kaydolur; ekrandaki QR ya da kodla telefondan eklenir, tek adresten (byd-gw.cloud.solucharger.com) her yerden ulaşılır
- Telefondan girişsiz açılış: Google hesabınla giriş yap, aracın arayüzü ikinci bir kod sormadan açılır; aynı aracı aile üyeleri de ekleyebilir
- Telefona bildirim: araçtan gelen uyarılar (gözetim, kapı, lastik, şarj) bulut üzerinden aracı ekleyen tüm hesapların telefonlarına gider; araç başına açıp kapatılabilir
- Bulut tüneli hep açık: uygulama kapansa da sürer, ağ kopunca kendiliğinden yeniden bağlanır
- Araçta "Bu araca erişen hesaplar" listesi ve erişimi kaldırma; "Kodu yenile"
- Güncellemeler artık OverDrive Bulut sunucusundan: uygulamada ve web ekranında "Güncelleme var", tek dokunuşla kurulum
- DiLink 5 (Tang / Sealion 7) iyileştirmeleri: vites, hız, kontak, 12 V akü, yarım kalan kayıtları kurtarma, uygulama bekçisi
