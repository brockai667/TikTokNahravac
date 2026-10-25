# TikTok Nahrávač

Malá Android appka (Kotlin, obyčajné Views), ktorá pomáha nahrať videá z faceless fabrík na TikTok priamo z telefónu (natívny upload) namiesto Buffera.

## Čo robí (v1)

- Z `state.json` každého kanála (zoznam je v `app/src/main/assets/channels.json`) ukáže 3 najnovšie epizódy: názov, číslo, termín v miestnom čase a značku HOTOVO, ak si ju na tomto telefóne označil.
- **Stiahnuť + text** – stiahne mp4 do `Movies/TikTokNahravac/` (vidieť v galérii) a skopíruje popis do schránky.
- **Otvoriť v TikToku** – znovu skopíruje popis, ukáže, na ktorý účet sa má prepnúť, a pošle video priamo do TikToku (`com.zhiliaoapp.musically`, potom `com.ss.android.ugc.trill`, inak bežný výber aplikácií). V TikToku prepni účet a popis vlož (podržať → Vložiť).
- **Označiť hotovo** – iba značka v telefóne (dá sa zrušiť).
- **Kalibrácia** – voliteľná služba dostupnosti, ktorá uloží strom obrazovky TikToku do súboru (príprava na automatické klikanie).

## Inštalácia APK

1. Na telefóne otvor stránku Releases repozitára (napr. cez appku GitHub) a stiahni `app-debug.apk` z vydania **latest**.
2. Povoľ inštaláciu z neznámych zdrojov pre aplikáciu, z ktorej inštaluješ, a APK nainštaluj.
3. Pri novej verzii najprv odinštaluj starú: debug APK sa podpisuje kľúčom, ktorý sa pri každom builde v CI vytvorí nanovo, takže "aktualizácia" cez starú verziu zvyčajne zlyhá. Ak to bude otravné, doplní sa pevný podpisový kľúč.

APK sa buduje automaticky (GitHub Actions, `.github/workflows/build.yml`) pri každom pushi do `main`; zároveň sa vydanie `latest` nahradí novým súborom.

## Zapnutie služby dostupnosti (kalibrácia)

Služba je predvolene **vypnutá**.

1. V appke ťukni na **Nastavenia dostupnosti** (alebo: Nastavenia → Dostupnosť → Nainštalované aplikácie) a zapni **TikTok Nahrávač**.
2. Android 13+: ak je prepínač sivý ("Obmedzené nastavenie"), choď do Nastavenia → Aplikácie → TikTok Nahrávač → ⋮ (vpravo hore) → **Povoliť obmedzené nastavenia** a skús znova.
3. V appke ťukni na **Nahrať strom obrazovky (kalibrácia)**, prepni do TikToku na obrazovku, ktorú chceš uložiť, a počkaj ~5 s. Objaví sa toast a notifikácia s cestou k súboru (`Android/data/sk.brock.nahravac/files/dumps/`).
4. Súbor pošli ďalej tlačidlom **Zdieľať posledný výpis** (na novších Androidoch sa do `Android/data` cez správcu súborov nedostaneš).

Služba zatiaľ nič nekliká ani nezbiera; číta len aktívne okno TikToku, keď o to požiadaš.

## TODO

- Automatické prepnutie účtu (`switchAccount(handle)`).
- Pridanie zvuku z TikToku a nastavenie hlasitosti.
- Automatické vloženie popisu (`pasteCaption()`) a uverejnenie (`tapPost()`, `tapNext()`).
- Všetky háčiky sú v `TikTokAccessibilityService.kt`, označené `TODO(kalibrácia)` a zatiaľ k ničomu nepripojené – doladia sa podľa výpisov obrazovky z reálneho telefónu.
- Pevný podpisový kľúč pre plynulé aktualizácie APK.
- Ďalšie fabriky pridať do `channels.json` (`name`, `tiktok`, `stateUrl`).

## Build lokálne

Potrebuje JDK 17, Android SDK (platforma 34) a Gradle 8.7: `gradle assembleDebug`. Gradle wrapper v repozitári nie je – CI používa `gradle/actions/setup-gradle` s Gradle 8.7.
