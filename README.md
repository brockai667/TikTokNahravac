# TikTok Nahrávač

Malá Android appka (Kotlin, obyčajné Views), ktorá nahráva videá z faceless fabrík na TikTok priamo z telefónu (natívny upload) namiesto Buffera. Verzia 2 sa **naučí postup nahrávania**: raz ho urobíš rukou, appka si kliky zapamätá a potom ich za teba zopakuje pre každý kanál (TikTok účet).

## Čo robí (v2)

- Z `state.json` každého kanála (zoznam je v `app/src/main/assets/channels.json`) ukáže 3 najnovšie epizódy: názov, číslo, termín v miestnom čase a značku HOTOVO, ak si ju na tomto telefóne označil.
- Hlavička ukazuje nájdený balík TikToku (`com.zhiliaoapp.musically`, `com.ss.android.ugc.trill` alebo `com.zhiliaoapp.musically.go`) a stav služby dostupnosti.
- **Stiahnuť + text** – stiahne mp4 do galérie (`Movies/TikTokNahravac/`) a skopíruje popis do schránky. Sťahovanie si robí appka sama (bez DownloadManagera), takže čerstvé video je v galérii vždy **najnovšie**.
- **Naučiť postup na tomto diele** – spustí učenie (pozri nižšie).
- **Nahrať automaticky** – pre jeden diel prehrá naučený postup. Dlhé podržanie tlačidla = naučiť postup znova.
- **Nahrať všetko (nové diely)** – pre každý kanál nahrá najnovší diel, ktorý ešte nie je označený ako hotový.
- **Otvoriť v TikToku** – ručné zdieľanie videa (ACTION_SEND). Pri chybe ukáže presný dôvod (trieda výnimky + správa).
- **Označiť hotovo** – značka v telefóne (dá sa zrušiť). Automatické nahranie ju nastaví samo.

## Inštalácia APK

1. Na telefóne otvor stránku Releases repozitára (napr. cez appku GitHub) a stiahni `app-debug.apk` z vydania **latest**.
2. Povoľ inštaláciu z neznámych zdrojov pre aplikáciu, z ktorej inštaluješ, a APK nainštaluj.
3. **Posledné odinštalovanie:** verzia 2 je podpísaná pevným kľúčom (`app/debug.keystore`, verejný „debug“ kľúč, nie tajomstvo). Staršie verzie boli podpísané kľúčom, ktorý sa pri každom builde vytvoril nanovo, preto raz treba starú verziu **odinštalovať**. Odteraz sa nové verzie inštalujú ako aktualizácia priamo cez starú.

APK sa buduje automaticky (GitHub Actions, `.github/workflows/build.yml`) pri každom pushi do `main`; zároveň sa vydanie `latest` nahradí novým súborom.

## Zapnutie služby dostupnosti

Služba je predvolene **vypnutá** a sama nič nerobí; kliky v TikToku nahráva a prehráva len keď to spustíš v appke.

1. V appke ťukni na **Nastavenia dostupnosti** (alebo: Nastavenia → Dostupnosť → Nainštalované aplikácie) a zapni **TikTok Nahrávač**.
2. Android 13+: ak je prepínač sivý („Obmedzené nastavenie“), choď do Nastavenia → Aplikácie → TikTok Nahrávač → ⋮ (vpravo hore) → **Povoliť obmedzené nastavenia** a skús znova.

## Ako naučiť postup

1. Pri niektorom diele ťukni na **Naučiť postup na tomto diele** a potvrď dialóg.
2. Appka stiahne video do galérie a skopíruje popis. Otvorí sa TikTok na domovskej stránke.
3. **Začni na INOM účte, než na ktorý nahrávaš**, aby sa zaznamenalo aj prepnutie účtu.
4. Rukou urob celé nahratie: prepni účet na cieľový, nahraj najnovšie video z galérie, (voliteľne pridaj zvuk a nastav hlasitosť), Ďalej, vlož popis (podržať → Vložiť), Uverejniť.
5. Vľavo uprostred je malá karta „● Učím sa – krok N“ (dá sa presunúť ťahaním za text). Kliknutím na **Uverejniť** sa učenie ukončí a postup sa uloží automaticky; tlačidlom **Hotovo** ho uložíš skôr, **Zrušiť** ho zahodí.
6. Ak postup neobsahuje prepnutie účtu, appka upozorní: pri viacerých účtoch postup nahraj znova a začni na inom účte, než na ktorý nahrávaš.

Zaznamenané kroky uvidíš cez **Zobraziť postup**, zmažeš ich cez **Zmazať postup**. Postup je v `filesDir/macro.json` na telefóne; dá sa mať len jeden (platí pre všetky kanály, účet sa dosadí podľa `channels.json`).

## Ako spustiť automatické nahrávanie

- **Nahrať všetko (nové diely)** alebo **Nahrať automaticky** pri diele. Appka diel čerstvo stiahne, skopíruje popis, otvorí TikTok a prehrá kroky. Vľavo je karta „▶ kanál: krok N/M“ s tlačidlom **Stop**, ktoré okamžite zastaví celú frontu.
- Ak sa krok nenájde, karta ponúkne **Znova** / **Preskočiť** / **Stop**.
- **Potvrdiť pred uverejnením** (predvolene zapnuté): pred posledným krokom karta ukáže „Skontroluj účet, video a popis“ s tlačidlami **Uverejniť** / **Zrušiť**. Potvrdenie sa vyžaduje aj vždy, keď bol niektorý krok preskočený. Vypnúť sa dá zaškrtávacím políčkom, ale odporúča sa to až po tom, čo postup niekoľkokrát prešiel bez problémov.
- Po uverejnení appka počká 6 s, označí diel ako hotový a pokračuje ďalším.

## Formát channels.json

```json
[
  {
    "name": "Plain Sight",
    "tiktok": "@plainsight.667",
    "stateUrl": "https://raw.githubusercontent.com/brockai667/HistoryUntold/main/state.json",
    "account": "plainsight.667",
    "format": "state"
  }
]
```

`account` (nepovinné) = text, ktorý TikTok ukazuje v prepínači účtov; ak chýba, použije sa `tiktok` bez „@“. `format` (nepovinné, predvolene `state`) = parser stavu fabriky; neznámy formát sa v zozname ukáže ako „Nepodporovaný formát“ a preskočí sa. Nový formát = ďalšia vetva `when` v `Repo.fetchEpisodes`.

`format: "drop"` = `tiktok_drop.json` z repa brockai667/tiktok-drop (`{repo: {cta, brand_hashtags, videos: [...]}}`); `dropKey` vyberie repo (napr. `coldcasedaily667`). Popis = `caption` (presný popis z fabriky, aký by išiel do Buffera); ak chýba, poskladá sa z `desc` (alebo názvu) + CTA + hashtagov + `suffix` (napr. kredit hudby CC BY). Kľúč „hotovo“ je názov súboru. `since` (nepovinné, ISO čas) skryje staršie diely – tie na TikTok ešte poslal Buffer, aby nevznikol dvojitý post.

## Známe obmedzenia

- Aktualizácia TikToku môže zmeniť tlačidlá (ich ID aj texty). Keď postup prestane fungovať, **nauč ho znova**.
- Nastavenie hlasitosti zvuku (posuvník) je „best effort“: ak ho TikTok nepodporuje cez službu dostupnosti, krok sa preskočí s upozornením na karte a hlasitosť nastav rukou.
- Náhodné okná TikToku (reklamy, upozornenia, „Skúsiť novú funkciu“) môžu prekryť očakávané tlačidlo. Zavri ich rukou a na karte stlač **Znova**.
- Video sa vyberá ako prvé v galérii (je najnovšie vďaka vlastnému sťahovaniu). Ak niektorý telefón radí galériu inak, môže sa vybrať iné video, preto ponechaj potvrdenie pred uverejnením zapnuté.
- Na Androide 8–9 appka pri prvom sťahovaní vypýta povolenie na úložisko.

## Skrytá ladiaca funkcia

Dlhé podržanie hlavičky „TikTok: …“ uloží strom obrazovky TikToku do súboru (po ~5 s v popredí; `Android/data/sk.brock.nahravac/files/dumps/`), dlhé podržanie riadku so stavom služby ho pošle ďalej. Hodí sa pri ladení, keď sa niektorý krok nedarí nájsť.

## Build lokálne

Potrebuje JDK 17, Android SDK (platforma 34) a Gradle 8.7: `gradle assembleDebug`. Gradle wrapper v repozitári nie je – CI používa `gradle/actions/setup-gradle` s Gradle 8.7.
