# TikTok Nahrávač

Malá Android appka (Kotlin, obyčajné Views), ktorá nahráva videá z faceless fabrík na TikTok priamo z telefónu (natívny upload) namiesto Buffera. Appka sa **raz naučí postup nahrávania** (ty ho raz urobíš rukou) a potom ho za teba zopakuje pre každý kanál. Účet kanála v TikToku prepína sama.

## Čo robí (0.4.0)

- Pre každý kanál z `app/src/main/assets/channels.json` (Plain Sight, Suddenly Rich, ColdCaseDaily, MindBlownDaily, BrainHeist – všetky účty sú prihlásené v jednom TikToku) načíta najnovšie diely z fabriky.
- Jedno veľké tlačidlo **▶ Nahrať N videí na TikTok**: pre každý kanál stiahne najnovší diel (obal + video) do galérie (`DCIM/TikTokNahravac`, video je najnovšie), v TikToku prepne na správny účet a prehrá naučený postup: + → Upload → prvé video → Next → (obal) → popis → Post.
- Hlavička ukazuje nájdený balík TikToku a stav služby dostupnosti.
- **Obal:** spolu s videom appka do galérie uloží aj obal epizódy (`<názov>-cover.jpg`, obrázok z fabriky; BrainHeist ho nemá, preto snímka z videa v čase 1 s). Obal sa ukladá vždy **pred** videom, takže najnovší obrázok je obal a najnovšia vec celkovo je **video**.
- **Posledné stiahnutie** – riadok pod tlačidlom: názov, veľkosť, priečinok, dĺžka a odkiaľ je obal, ako ich vidí galéria. **Otvoriť galériu** otvorí naposledy stiahnuté video.

## Inštalácia APK

1. Na telefóne otvor stránku Releases repozitára (napr. cez appku GitHub) a stiahni `app-debug.apk` z vydania **latest**.
2. Povoľ inštaláciu z neznámych zdrojov pre aplikáciu, z ktorej inštaluješ, a APK nainštaluj (ide ako aktualizácia cez staršiu verziu – podpisový kľúč je pevný, `app/debug.keystore`, verejný „debug“ kľúč, nie tajomstvo).

APK sa buduje automaticky (GitHub Actions, `.github/workflows/build.yml`) pri každom pushi do `main`; zároveň sa vydanie `latest` nahradí novým súborom.

## Zapnutie služby dostupnosti

Služba je predvolene **vypnutá** a sama nič nerobí; v TikToku ťuká len keď to spustíš v appke.

1. V appke ťukni na **Nastavenia dostupnosti** (alebo: Nastavenia → Dostupnosť → Nainštalované aplikácie) a zapni **TikTok Nahrávač**.
2. Android 13+: ak je prepínač sivý („Obmedzené nastavenie“), choď do Nastavenia → Aplikácie → TikTok Nahrávač → ⋮ (vpravo hore) → **Povoliť obmedzené nastavenia** a skús znova.

## Používanie

Stačí jedno tlačidlo **▶ Nahrať N videí na TikTok** (úplne hore). N = počet kanálov, ktorých najnovší diel ešte nie je nahratý. Po ťuknutí appka skontroluje službu a TikTok, obnoví zoznamy kanálov a:

- ak nie je čo nahrať, ukáže „Nie je čo nahrať“,
- ak ešte nie je naučený postup, ponúkne jeho naučenie na prvom čakajúcom diele (raz, pozri nižšie),
- inak pre každý čakajúci kanál: stiahne diel → prepne účet → otvorí TikTok na domovskej stránke → prehrá postup → po Post počká 20 s a označí diel ako nahratý.

Vľavo je karta „▶ kanál: krok N/M“ s tlačidlom **Stop** (okamžite zastaví všetko). Kartu môžeš presunúť ťahaním za text.

### Prvé spustenie: čo sa appka naučí

1. **Prepnutie účtu – len ak treba.** Appka otvorí Profil a prečíta meno účtu v hlavičke. Ak je to už správny účet, nič neprepína. Inak skúsi sama: tlačidlo „Switch account“ hore, ťuk na meno aktuálneho účtu (otvorí zoznam účtov) a v zozname ťukne na účet kanála. Ak nič z toho nezaberie, karta napíše **„Prepni účet na @… – appka sa to raz naučí“**: prepni účet rukou (napr. ☰ → Settings and privacy → … → Switch account → účet). Ťuk na účet kanála cestu uloží (`filesDir/switch.json`) a nabudúce ju appka prejde sama.
2. **Nahratie – raz.** Keď karta ukáže **„● Učím sa…“**, urob rukou: **+** → **Upload** → **PRVÉ (najnovšie) video** → **Next** … (voliteľne Edit cover → pridať z galérie → PRVÝ obrázok = obal → Save) → **ťukni do poľa popisu** (appka popis vloží sama, nič nepíš) → **Post**. Ťuk na Post postup uloží (`filesDir/macro.json`) a video sa uverejní.
   - Appka zachytáva každý dotyk (ťuk, podržanie, potiahnutie) a hneď ho pošle ďalej do TikToku – ťukaj pokojne, len o chlp pomalšie.
   - Na karte: **Späť** = tlačidlo späť (zapíše sa ako krok), **Hotovo** = uložiť skôr (bez Post), **Zrušiť** = zahodiť. Systémové gesto späť sa nezapisuje – použi Späť na karte.
   - Písanie na klávesnici sa nezapisuje, ťuky do iných appiek a systémových okien tiež nie.

Učenie sa dá spustiť aj ručne: pri diele **Viac možností** → **Naučiť postup na tomto diele** (kým postup nie je naučený), inak dlhé podržanie **Nahrať automaticky**. **Zobraziť postup** ukáže kroky (aj prepnutie účtu), **Zmazať postup** zmaže oba – appka sa ich naučí znova. Postupy zo starších verzií (pred 0.4.0) sa nenačítajú, treba ich raz naučiť znova.

### Ako appka prehráva

- Pred každým krokom počká, kým obrazovka TikToku vyzerá ako pri učení (aspoň 40 % textov z „podpisu obrazovky“, najviac 15 s; po výbere videa 30 s).
- Prvok hľadá podľa presného textu (napr. „Next“, „Post“) a ťukne do jeho stredu; ak text nemá, ťukne na zaznamenané miesto. Zahmlené ID TikToku sa nepoužívajú. Na „Your Story“ appka nikdy neťukne.
- Pole popisu vyplní sama. Pred Post sa pýta (**Uverejniť** / **Zrušiť**), ak je zaškrtnuté **Potvrdiť pred uverejnením**, ak sa niektorý krok preskočil alebo ak popis nie je na obrazovke.
- **Vyskakovacie okná:** ak obrazovka dlhšie ako 4 s nesedí, appka každé ~3 s skúsi zavrieť okno tlačidlom s presným textom Not now, Nie teraz, Teraz nie, Later, Neskôr, Skip, Preskočiť, Got it, Rozumiem, Close, Zavrieť alebo Dismiss. Ak si také okno zatvoril pri učení a pri prehrávaní sa neukáže, krok sa ticho preskočí.
- Ak sa krok nenájde, karta ponúkne **Znova** / **Preskočiť** / **Stop** a appka uloží diagnostiku. Ak sa nepodarí prepnúť účet: **Pokračovať** (prepol si ručne) / **Preskočiť kanál** / **Stop**.

## Stav v appke

- Nad tlačidlom: **„Na nahratie: N (Plain Sight #4, …)“**, alebo **„Všetko je nahraté ✔“**. „⚠ Nenačítané“ = kanál sa nepodarilo načítať (sieť).
- Karta kanála: „názov · @handle“ a pri dieloch:
  - **⏳ Čaká na nahratie** – najnovší diel, ktorý appka ešte nenahrala (nahrá ho tlačidlo hore),
  - **✔ Nahraté appkou d.M. HH:mm** – nahraté z tohto telefónu (čas, kedy appka diel označila),
  - **– staršie, nenahráva sa** – appka nahráva vždy len najnovší diel kanála,
  - **„Staršie diely (pred …) poslal na TikTok Buffer.“** – pri kanáloch s `since` (staršie diely sa vôbec neukazujú).
- **Viac možností** na karte rozbalí tlačidlá pri dieloch: Stiahnuť + text, Naučiť postup, Nahrať automaticky (len tento diel), Otvoriť v TikToku (ručné zdieľanie), Označiť hotovo / Zrušiť označenie.

## Diagnostika

Keď sa krok nenájde alebo sa nepodarí prepnúť účet, appka uloží súbor `Download/TikTokNahravac/diag-<dátum-čas>.json` (Android 10+): ktorý krok, podpis obrazovky a zoznam viditeľných prvkov TikToku (text, popis, trieda, poloha). Tlačidlo **Diagnostika** dole v appke pošle posledných 5 súborov (WhatsApp, Gmail…), aby sa dal postup opraviť bez telefónu v ruke.

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

Účet kanála appka spozná podľa kľúčov: `account` (nepovinné, ak TikTok ukazuje iné meno), `tiktok` bez „@“ a `name` – všetko malými písmenami len s písmenami a číslicami (kratšie než 5 znakov sa ignorujú). Text na obrazovke patrí kanálu, ak kľúč obsahuje alebo je začiatkom kľúča (napr. „Plain Sight“ → `plainsight667`). `format` (nepovinné, predvolene `state`) = parser stavu fabriky; neznámy formát sa ukáže ako „Nepodporovaný formát“. Nový formát = ďalšia vetva `when` v `Repo.fetchEpisodes`.

`format: "drop"` = `tiktok_drop.json` z repa brockai667/tiktok-drop (`{repo: {cta, brand_hashtags, videos: [...]}}`); `dropKey` vyberie repo (napr. `coldcasedaily667`). Popis = `caption` (presný popis z fabriky, aký by išiel do Buffera); ak chýba, poskladá sa z `desc` (alebo názvu) + CTA + hashtagov + `suffix` (napr. kredit hudby CC BY). Kľúč „hotovo“ je názov súboru. `cover` (nepovinné) = šablóna adresy obalu: `{video_base}` = adresa videa bez „.mp4“ (napr. `{video_base}-cover.jpg`), `{slug}` = názov súboru videa bez „.mp4“. Bez šablóny sa obal vezme zo snímky videa. `since` (nepovinné, ISO čas) skryje staršie diely – tie na TikTok ešte poslal Buffer, aby nevznikol dvojitý post.

## Známe obmedzenia

- Aktualizácia TikToku môže zmeniť obrazovky a texty tlačidiel. Keď postup prestane fungovať, pošli Diagnostiku alebo postup zmaž a nauč znova.
- Učenie počas gesta (~0,1 s po každom dotyku) neprijíma ďalší dotyk – ťukaj pokojne. Viac prstov naraz sa nezapisuje.
- Video sa vyberá ako prvé v galérii (je najnovšie vďaka vlastnému sťahovaniu). Ak niektorý telefón radí galériu inak, zapni potvrdenie pred uverejnením.
- Ak sa meno účtu v TikToku vôbec nepodobá na `name` ani `tiktok`, doplň do `channels.json` pole `account`.
- Diagnostika sa na Androide 8–9 neukladá. Na Androide 8–9 appka pri prvom sťahovaní vypýta povolenie na úložisko.

## Skrytá ladiaca funkcia

Dlhé podržanie hlavičky „TikTok: …“ uloží strom obrazovky TikToku do súboru (po ~5 s v popredí; `Android/data/sk.brock.nahravac/files/dumps/`), dlhé podržanie riadku so stavom služby ho pošle ďalej.

## Build lokálne

Potrebuje JDK 17, Android SDK (platforma 34) a Gradle 8.7: `gradle assembleDebug`. Gradle wrapper v repozitári nie je – CI používa `gradle/actions/setup-gradle` s Gradle 8.7.
