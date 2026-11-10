package com.youss17x.playbeam

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App languages. English is the default; more can be added later. */
enum class Lang(val code: String, val nativeName: String, val rtl: Boolean) {
    EN("en", "English", false),
    FR("fr", "Français", false),
    AR("ar", "العربية", true),
}

/** The current language. Compose screens re-draw automatically when it changes. */
object I18n {
    var lang by mutableStateOf(Lang.EN)
        private set

    val strings: Strings
        get() = when (lang) {
            Lang.EN -> En
            Lang.FR -> Fr
            Lang.AR -> Ar
        }

    fun load(ctx: Context) {
        val code = Store.prefs(ctx).getString("lang", null)
        lang = Lang.entries.firstOrNull { it.code == code } ?: Lang.EN
    }

    fun set(ctx: Context, l: Lang) {
        Store.prefs(ctx).edit().putString("lang", l.code).apply()
        lang = l
    }
}

/** Shortcut used everywhere: S.cancel, S.files(3)… */
val S: Strings get() = I18n.strings

/** Every text of the app. Adding a member forces every language to translate it. */
abstract class Strings {
    // general
    abstract val cancel: String
    abstract val done: String
    abstract val save: String
    abstract val close: String
    abstract val stop: String
    abstract val name: String
    abstract val notSet: String
    abstract val choose: String
    abstract val change: String
    abstract val delete: String
    abstract val clear: String
    abstract val send: String
    abstract val create: String
    abstract val rename: String
    abstract val move: String
    abstract val copy: String

    // tabs
    abstract val tabUpload: String
    abstract val tabFiles: String
    abstract val tabPayload: String
    abstract val tabHistory: String
    abstract val tabSettings: String

    // files tab without PS5
    abstract val noPs5Title: String
    abstract val noPs5Body: String
    abstract val openSettings: String
    abstract val uploadHere: String

    // upload
    abstract fun files(n: Int): String
    abstract val oneFile: String
    abstract val chipSending: String
    abstract val chipNoPs5: String
    abstract val sendToPs5: String
    abstract val chooseWhatTitle: String
    abstract val chooseWhatSub: String
    abstract val folderReady: String
    abstract val oneFileReady: String
    abstract val readyToSend: String
    abstract val console: String
    abstract val destination: String
    abstract val transfer: String
    abstract val transferFastSub: String
    abstract val transferFtpSub: String
    abstract val optResume: String
    abstract fun optConn(n: Int): String
    abstract val optUnzip: String
    abstract val options: String
    abstract val addToQueue: String
    abstract val uploadToPs5: String
    abstract val setPs5First: String
    abstract val nowSending: String
    abstract val lastUpload: String
    abstract fun progressLine(pct: Int, done: String, total: String): String
    abstract fun filesOf(done: Int, total: Int): String
    abstract val stopAll: String
    abstract fun waiting(n: Int): String
    abstract val whatToSend: String
    abstract val aFolder: String
    abstract val aFolderSub: String
    abstract val filesOpt: String
    abstract val filesOptSub: String
    abstract val pathOnPs5: String
    abstract val quickPicks: String
    abstract val browsePs5: String
    abstract val presetPs5: String
    abstract val presetUsb: String

    // console editor
    abstract fun searching(pct: Int): String
    abstract val findMyPs5: String
    abstract val searchFailed: String
    abstract val noPs5Found: String
    abstract val ps5Ip: String
    abstract val ftpPort: String
    abstract fun portNote(port: Int): String
    abstract val myPs5: String
    abstract fun ps5N(n: Int): String
    abstract val saveThisPs5: String

    // settings
    abstract val settings: String
    abstract val secPs5: String
    abstract val secServer: String
    abstract val secTransfers: String
    abstract val resumeTitle: String
    abstract val resumeSub: String
    abstract val extractTitle: String
    abstract val extractSub: String
    abstract val connections: String
    abstract val connectionsSub: String
    abstract val secDownloads: String
    abstract val downloadFolder: String
    abstract val notChosen: String
    abstract val downloadFolderSub: String
    abstract val secAbout: String
    abstract val version: String
    abstract val serverPort: String
    abstract val sourceCode: String
    abstract val secLanguage: String

    // PlayBeam Server card
    abstract val serverCardSub: String
    abstract val running: String
    abstract val off: String
    abstract val startOnPs5: String
    abstract val stopServer: String
    abstract val sendingServer: String
    abstract val starting: String
    abstract val stopping: String
    abstract val serverRunningMsg: String
    abstract val serverNoAnswer: String
    abstract val serverStopped: String
    abstract val sendFailed: String
    abstract fun loaderNotRunning(msg: String, port: Int): String
    abstract fun serverLoaderNote(port: Int): String
    abstract val setPs5InSettings: String

    // payload
    abstract val payloadIntro: String
    abstract val loaderPort: String
    abstract val loaderNote: String
    abstract val choosePayload: String
    abstract fun sendingX(name: String): String
    abstract val recent: String
    abstract fun payloadSent(name: String, size: String, host: String, port: String): String
    abstract fun payloadFailed(msg: String): String
    abstract val setPs5OrFind: String

    // history
    abstract val history: String
    abstract val noUploads: String
    abstract val noUploadsSub: String
    abstract fun historyLine(count: Int, size: String, dest: String): String
    abstract val clearHistoryQ: String
    abstract val clearHistoryBody: String

    // files / browser
    abstract val useThisFolder: String
    abstract val extended: String
    abstract val stopped: String
    abstract fun alreadyExistsHere(name: String): String
    abstract fun moving(name: String): String
    abstract fun moved(name: String): String
    abstract fun movedTo(name: String, dir: String): String
    abstract fun copying(name: String): String
    abstract fun copied(name: String): String
    abstract fun savedToPhone(name: String, size: String): String
    abstract val downloadStopped: String
    abstract val ps5Files: String
    abstract fun via(how: String): String
    abstract fun downloading(name: String): String
    abstract val emptyFolder: String
    abstract val pasteHere: String
    abstract val newFolder: String
    abstract fun deleteQ(name: String): String
    abstract val deleteFolderBody: String
    abstract val deleteFileBody: String
    abstract fun free(size: String): String
    abstract fun ofTotal(done: String, total: String): String
    abstract val homebrewApps: String
    abstract val downloadToPhone: String
    abstract val cut: String
    abstract val moveTo: String
    abstract val details: String
    abstract fun moveX(name: String): String
    abstract val toFolder: String
    abstract val thisFolder: String
    abstract val ps5Homebrew: String
    abstract val usbHomebrew: String
    abstract val moveNote: String
    abstract val type: String
    abstract val homebrewFolder: String
    abstract val folder: String
    abstract fun fileOfType(ext: String): String
    abstract val file: String
    abstract val location: String
    abstract val size: String
    abstract val sizeUnknown: String
    abstract val bytes: String
    abstract val contains: String
    abstract fun containsLine(count: Int, dirs: Int): String
    abstract val modified: String

    // upload service / notifications
    abstract val waitingNotif: String
    abstract val scanning: String
    abstract fun connectingTo(host: String): String
    abstract fun nWaiting(n: Int): String
    abstract val zipsNotExtracted: String
    abstract fun extractingPct(name: String, i: Int, n: Int, pct: Int): String
    abstract fun extracting(name: String): String
    abstract fun extractedNote(count: Int, zips: Int): String
    abstract fun skippedNote(size: String): String
    abstract val viaServer: String
    abstract fun uploadComplete(via: String, count: Int, size: String): String
    abstract val uploadStopped: String
    abstract val chProgress: String
    abstract val chDone: String
    abstract fun sentToPs5(label: String): String
    abstract fun notCompleted(label: String): String

    // appearance & updates
    abstract val secAppearance: String
    abstract val themeSystem: String
    abstract val themeLight: String
    abstract val themeDark: String
    abstract val checkUpdates: String
    abstract val updates: String
    abstract val autoCheck: String
    abstract val autoCheckSub: String
    abstract val checking: String
    abstract val upToDate: String
    abstract val updateAvailable: String
    abstract fun updateVersions(new: String, current: String): String
    abstract val whatsNew: String
    abstract val update: String
    abstract val later: String
    abstract val skipVersion: String
    abstract val downloadingUpdate: String
    abstract val allowInstall: String
    abstract val updateCheckFailed: String

    // PS5 info & about
    abstract val ps5Info: String
    abstract val liveSensors: String
    abstract val autoRefresh: String
    abstract val cpuTemp: String
    abstract val socTemp: String
    abstract val cpuFreq: String
    abstract val uptime: String
    abstract fun firmware(fw: String): String
    abstract val storage: String
    abstract val internalStorage: String
    abstract fun usedOf(used: String, total: String): String
    abstract val infoNeedsServer: String
    abstract val appTagline: String
    abstract val openSource: String
    abstract val madeBy: String
    abstract fun discordCopied(user: String): String

    // errors
    abstract val notOnWifi: String
    abstract val serverErrorGeneric: String
    abstract fun cantReach(host: String): String
    abstract val somethingWrong: String
}

object En : Strings() {
    override val cancel = "Cancel"
    override val done = "Done"
    override val save = "Save"
    override val close = "Close"
    override val stop = "Stop"
    override val name = "Name"
    override val notSet = "Not set"
    override val choose = "Choose"
    override val change = "Change"
    override val delete = "Delete"
    override val clear = "Clear"
    override val send = "Send"
    override val create = "Create"
    override val rename = "Rename"
    override val move = "Move"
    override val copy = "Copy"

    override val tabUpload = "Upload"
    override val tabFiles = "Files"
    override val tabPayload = "Payload"
    override val tabHistory = "History"
    override val tabSettings = "Settings"

    override val noPs5Title = "No PS5 set yet"
    override val noPs5Body = "Add your PS5 in Settings to browse its files."
    override val openSettings = "Open Settings"
    override val uploadHere = "Upload here"

    override fun files(n: Int) = if (n == 1) "1 file" else "$n files"
    override val oneFile = "1 file"
    override val chipSending = "Sending"
    override val chipNoPs5 = "No PS5 set"
    override val sendToPs5 = "Send to PS5"
    override val chooseWhatTitle = "Choose what to send"
    override val chooseWhatSub = "A folder or files from your phone"
    override val folderReady = "Folder ready to send"
    override val oneFileReady = "1 file ready to send"
    override val readyToSend = "Ready to send"
    override val console = "Console"
    override val destination = "Destination"
    override val transfer = "Transfer"
    override val transferFastSub = "Fastest — server running on the PS5"
    override val transferFtpSub = "Start PlayBeam Server in Settings for faster uploads"
    override val optResume = "Resume"
    override fun optConn(n: Int) = "$n conn."
    override val optUnzip = "Unzip"
    override val options = "Options"
    override val addToQueue = "Add to queue"
    override val uploadToPs5 = "Upload to PS5"
    override val setPs5First = "Set your PS5 first: tap Console."
    override val nowSending = "NOW SENDING"
    override val lastUpload = "LAST UPLOAD"
    override fun progressLine(pct: Int, done: String, total: String) = "$pct% · $done of $total"
    override fun filesOf(done: Int, total: Int) = "$done / $total files"
    override val stopAll = "Stop all"
    override fun waiting(n: Int) = "Waiting · $n"
    override val whatToSend = "What do you want to send?"
    override val aFolder = "A folder"
    override val aFolderSub = "Everything inside, subfolders too"
    override val filesOpt = "Files"
    override val filesOptSub = "Pick one or several files"
    override val pathOnPs5 = "Path on PS5"
    override val quickPicks = "Quick picks"
    override val browsePs5 = "Browse PS5"
    override val presetPs5 = "PS5 · homebrew"
    override val presetUsb = "USB · homebrew"

    override fun searching(pct: Int) = "Searching… $pct%"
    override val findMyPs5 = "Find my PS5"
    override val searchFailed = "Search failed"
    override val noPs5Found = "No PS5 found. Make sure PlayBeam Server or an FTP server is running on your PS5 and both devices are on the same Wi-Fi."
    override val ps5Ip = "PS5 IP"
    override val ftpPort = "FTP port"
    override fun portNote(port: Int) = "PlayBeam Server always uses port $port automatically. The FTP port is only used when the server isn't running (usually 2121)."
    override val myPs5 = "My PS5"
    override fun ps5N(n: Int) = "PS5 $n"
    override val saveThisPs5 = "Save this PS5"

    override val settings = "Settings"
    override val secPs5 = "PS5"
    override val secServer = "PlayBeam Server"
    override val secTransfers = "Transfers"
    override val resumeTitle = "Resume unfinished files"
    override val resumeSub = "Skip files already on the PS5, continue partial ones"
    override val extractTitle = "Extract ZIPs on PS5"
    override val extractSub = "Unzip uploaded .zip files, then delete the .zip (needs PlayBeam Server)"
    override val connections = "Connections"
    override val connectionsSub = "More is faster for many small files"
    override val secDownloads = "Downloads"
    override val downloadFolder = "Phone download folder"
    override val notChosen = "Not chosen"
    override val downloadFolderSub = "Where files downloaded from the PS5 are saved"
    override val secAbout = "About"
    override val version = "Version"
    override val serverPort = "PlayBeam Server port"
    override val sourceCode = "Source code"
    override val secLanguage = "Language"

    override val serverCardSub = "Faster uploads · extracts ZIPs on the PS5"
    override val running = "Running"
    override val off = "Off"
    override val startOnPs5 = "Start on PS5"
    override val stopServer = "Stop server"
    override val sendingServer = "Sending PlayBeam Server…"
    override val starting = "Starting…"
    override val stopping = "Stopping…"
    override val serverRunningMsg = "✅ PlayBeam Server is running — uploads now use it automatically"
    override val serverNoAnswer = "Sent, but the server didn't answer. Check the PS5 notifications."
    override val serverStopped = "PlayBeam Server stopped"
    override val sendFailed = "Send failed"
    override fun loaderNotRunning(msg: String, port: Int) = "$msg. Is the ELF loader running on port $port?"
    override fun serverLoaderNote(port: Int) = "Started through the ELF loader on port $port (change it in the Payload tab)."
    override val setPs5InSettings = "Set your PS5 first in Settings."

    override val payloadIntro = "Send any .elf / .bin payload to the PS5 loader. PlayBeam Server is in Settings."
    override val loaderPort = "Loader port"
    override val loaderNote = "The ELF loader usually listens on 9021; some .bin loaders use 9020."
    override val choosePayload = "Choose a payload and send"
    override fun sendingX(name: String) = "Sending $name…"
    override val recent = "Recent"
    override fun payloadSent(name: String, size: String, host: String, port: String) = "✅ $name sent ($size) to $host:$port"
    override fun payloadFailed(msg: String) = "$msg. Is the payload loader running on the PS5?"
    override val setPs5OrFind = "Set your PS5 first in Settings (or use Find my PS5)."

    override val history = "History"
    override val noUploads = "No uploads yet"
    override val noUploadsSub = "Everything you send to the PS5 will be listed here."
    override fun historyLine(count: Int, size: String, dest: String) = "${files(count)} · $size → $dest"
    override val clearHistoryQ = "Clear history?"
    override val clearHistoryBody = "This removes the upload history from this phone. Files on the PS5 are not touched."

    override val useThisFolder = "Use this folder"
    override val extended = "Extended"
    override val stopped = "Stopped"
    override fun alreadyExistsHere(name: String) = "“$name” already exists here"
    override fun moving(name: String) = "Moving $name"
    override fun moved(name: String) = "✅ Moved $name"
    override fun movedTo(name: String, dir: String) = "✅ Moved $name to $dir"
    override fun copying(name: String) = "Copying $name"
    override fun copied(name: String) = "✅ Copied $name"
    override fun savedToPhone(name: String, size: String) = "✅ $name saved to your phone ($size)"
    override val downloadStopped = "Download stopped"
    override val ps5Files = "PS5 files"
    override fun via(how: String) = "via $how"
    override fun downloading(name: String) = "Downloading $name"
    override val emptyFolder = "Empty folder"
    override val pasteHere = "Paste here"
    override val newFolder = "New folder"
    override fun deleteQ(name: String) = "Delete $name?"
    override val deleteFolderBody = "The folder and everything inside it will be deleted from the PS5. This can't be undone."
    override val deleteFileBody = "This file will be deleted from the PS5. This can't be undone."
    override fun free(size: String) = "$size free"
    override fun ofTotal(done: String, total: String) = "$done of $total"
    override val homebrewApps = "Homebrew apps"
    override val downloadToPhone = "Download to phone"
    override val cut = "Cut"
    override val moveTo = "Move to…"
    override val details = "Details"
    override fun moveX(name: String) = "Move $name"
    override val toFolder = "To folder"
    override val thisFolder = "This folder"
    override val ps5Homebrew = "PS5 homebrew"
    override val usbHomebrew = "USB homebrew"
    override val moveNote = "Moving to another drive copies everything, then removes the original."
    override val type = "Type"
    override val homebrewFolder = "Homebrew folder"
    override val folder = "Folder"
    override fun fileOfType(ext: String) = "$ext file"
    override val file = "File"
    override val location = "Location"
    override val size = "Size"
    override val sizeUnknown = "Unknown (needs PlayBeam Server)"
    override val bytes = "bytes"
    override val contains = "Contains"
    override fun containsLine(count: Int, dirs: Int) = "${files(count)}, $dirs folders"
    override val modified = "Modified"

    override val waitingNotif = "Waiting…"
    override val scanning = "Scanning files…"
    override fun connectingTo(host: String) = "Connecting to $host…"
    override fun nWaiting(n: Int) = " • $n waiting"
    override val zipsNotExtracted = " — ZIPs not extracted (start PlayBeam Server for that)"
    override fun extractingPct(name: String, i: Int, n: Int, pct: Int) = "Extracting $name ($i/$n) — $pct%"
    override fun extracting(name: String) = "Extracting $name"
    override fun extractedNote(count: Int, zips: Int) = " — extracted ${files(count)} from $zips ZIP(s)"
    override fun skippedNote(size: String) = " — $size were already on the PS5"
    override val viaServer = " via PlayBeam Server"
    override fun uploadComplete(via: String, count: Int, size: String) = "✅ Upload complete$via: ${files(count)} ($size)"
    override val uploadStopped = "⏹ Upload stopped"
    override val chProgress = "Upload progress"
    override val chDone = "Upload finished"
    override fun sentToPs5(label: String) = "$label sent to PS5"
    override fun notCompleted(label: String) = "$label — not completed"

    override val secAppearance = "Appearance"
    override val themeSystem = "System"
    override val themeLight = "Light"
    override val themeDark = "Dark"
    override val updates = "Updates"
    override val checkUpdates = "Check for updates"
    override val autoCheck = "Check automatically"
    override val autoCheckSub = "Look for a new version when the app opens"
    override val checking = "Checking…"
    override val upToDate = "You have the latest version"
    override val updateAvailable = "Update available"
    override fun updateVersions(new: String, current: String) = "Version $new is out (you have $current)."
    override val whatsNew = "What's new"
    override val update = "Update"
    override val later = "Later"
    override val skipVersion = "Skip this version"
    override val downloadingUpdate = "Downloading update…"
    override val allowInstall = "Allow PlayBeam to install apps, then tap Update again."
    override val updateCheckFailed = "Couldn't check for updates"

    override val ps5Info = "PS5 info"
    override val liveSensors = "Live sensors"
    override val autoRefresh = "Updates automatically"
    override val cpuTemp = "CPU"
    override val socTemp = "SoC"
    override val cpuFreq = "CPU frequency"
    override val uptime = "Uptime"
    override fun firmware(fw: String) = "Firmware $fw"
    override val storage = "Storage"
    override val internalStorage = "PS5 (internal)"
    override fun usedOf(used: String, total: String) = "$used used of $total"
    override val infoNeedsServer = "Start PlayBeam Server to see your PS5's details, temperatures and storage."
    override val appTagline = "Send files and folders from your phone to your jailbroken PS5 — fast, simple and free."
    override val openSource = "Open source"
    override val madeBy = "Made by youss17x"

    override fun discordCopied(user: String) = "Discord username “$user” copied"
    override val notOnWifi = "Your phone isn't on Wi-Fi. Connect it to the same Wi-Fi as the PS5."
    override val serverErrorGeneric = "PlayBeam Server error"
    override fun cantReach(host: String) = "Can't reach the PS5 at $host. Is it on, and is PlayBeam Server (or FTP) running?"
    override val somethingWrong = "Something went wrong"
}

object Fr : Strings() {
    override val cancel = "Annuler"
    override val done = "Terminé"
    override val save = "Enregistrer"
    override val close = "Fermer"
    override val stop = "Arrêter"
    override val name = "Nom"
    override val notSet = "Non défini"
    override val choose = "Choisir"
    override val change = "Changer"
    override val delete = "Supprimer"
    override val clear = "Effacer"
    override val send = "Envoyer"
    override val create = "Créer"
    override val rename = "Renommer"
    override val move = "Déplacer"
    override val copy = "Copier"

    override val tabUpload = "Envoi"
    override val tabFiles = "Fichiers"
    override val tabPayload = "Payload"
    override val tabHistory = "Historique"
    override val tabSettings = "Réglages"

    override val noPs5Title = "Aucune PS5 configurée"
    override val noPs5Body = "Ajoutez votre PS5 dans Réglages pour parcourir ses fichiers."
    override val openSettings = "Ouvrir les réglages"
    override val uploadHere = "Envoyer ici"

    override fun files(n: Int) = if (n <= 1) "$n fichier" else "$n fichiers"
    override val oneFile = "1 fichier"
    override val chipSending = "Envoi"
    override val chipNoPs5 = "Aucune PS5"
    override val sendToPs5 = "Envoyer vers la PS5"
    override val chooseWhatTitle = "Choisir quoi envoyer"
    override val chooseWhatSub = "Un dossier ou des fichiers du téléphone"
    override val folderReady = "Dossier prêt à envoyer"
    override val oneFileReady = "1 fichier prêt à envoyer"
    override val readyToSend = "Prêt à envoyer"
    override val console = "Console"
    override val destination = "Destination"
    override val transfer = "Transfert"
    override val transferFastSub = "Le plus rapide — serveur actif sur la PS5"
    override val transferFtpSub = "Lancez PlayBeam Server dans Réglages pour aller plus vite"
    override val optResume = "Reprise"
    override fun optConn(n: Int) = "$n conn."
    override val optUnzip = "Décompresser"
    override val options = "Options"
    override val addToQueue = "Ajouter à la file"
    override val uploadToPs5 = "Envoyer vers la PS5"
    override val setPs5First = "Configurez d'abord votre PS5 : touchez Console."
    override val nowSending = "ENVOI EN COURS"
    override val lastUpload = "DERNIER ENVOI"
    override fun progressLine(pct: Int, done: String, total: String) = "$pct % · $done sur $total"
    override fun filesOf(done: Int, total: Int) = "$done / $total fichiers"
    override val stopAll = "Tout arrêter"
    override fun waiting(n: Int) = "En attente · $n"
    override val whatToSend = "Que voulez-vous envoyer ?"
    override val aFolder = "Un dossier"
    override val aFolderSub = "Tout son contenu, sous-dossiers compris"
    override val filesOpt = "Des fichiers"
    override val filesOptSub = "Choisissez un ou plusieurs fichiers"
    override val pathOnPs5 = "Chemin sur la PS5"
    override val quickPicks = "Raccourcis"
    override val browsePs5 = "Parcourir la PS5"
    override val presetPs5 = "PS5 · homebrew"
    override val presetUsb = "USB · homebrew"

    override fun searching(pct: Int) = "Recherche… $pct %"
    override val findMyPs5 = "Trouver ma PS5"
    override val searchFailed = "La recherche a échoué"
    override val noPs5Found = "Aucune PS5 trouvée. Vérifiez que PlayBeam Server ou un serveur FTP tourne sur la PS5 et que les deux appareils sont sur le même Wi-Fi."
    override val ps5Ip = "IP de la PS5"
    override val ftpPort = "Port FTP"
    override fun portNote(port: Int) = "PlayBeam Server utilise toujours le port $port automatiquement. Le port FTP ne sert que si le serveur n'est pas lancé (souvent 2121)."
    override val myPs5 = "Ma PS5"
    override fun ps5N(n: Int) = "PS5 $n"
    override val saveThisPs5 = "Enregistrer cette PS5"

    override val settings = "Réglages"
    override val secPs5 = "PS5"
    override val secServer = "PlayBeam Server"
    override val secTransfers = "Transferts"
    override val resumeTitle = "Reprendre les fichiers inachevés"
    override val resumeSub = "Ignore les fichiers déjà sur la PS5 et reprend les fichiers incomplets"
    override val extractTitle = "Décompresser les ZIP sur la PS5"
    override val extractSub = "Décompresse les .zip envoyés puis les supprime (nécessite PlayBeam Server)"
    override val connections = "Connexions"
    override val connectionsSub = "Plus rapide avec beaucoup de petits fichiers"
    override val secDownloads = "Téléchargements"
    override val downloadFolder = "Dossier du téléphone"
    override val notChosen = "Non choisi"
    override val downloadFolderSub = "Où sont enregistrés les fichiers téléchargés depuis la PS5"
    override val secAbout = "À propos"
    override val version = "Version"
    override val serverPort = "Port de PlayBeam Server"
    override val sourceCode = "Code source"
    override val secLanguage = "Langue"

    override val serverCardSub = "Envois plus rapides · décompresse les ZIP sur la PS5"
    override val running = "Actif"
    override val off = "Arrêté"
    override val startOnPs5 = "Lancer sur la PS5"
    override val stopServer = "Arrêter le serveur"
    override val sendingServer = "Envoi de PlayBeam Server…"
    override val starting = "Démarrage…"
    override val stopping = "Arrêt…"
    override val serverRunningMsg = "✅ PlayBeam Server est actif — les envois l'utilisent automatiquement"
    override val serverNoAnswer = "Envoyé, mais le serveur ne répond pas. Regardez les notifications de la PS5."
    override val serverStopped = "PlayBeam Server arrêté"
    override val sendFailed = "Échec de l'envoi"
    override fun loaderNotRunning(msg: String, port: Int) = "$msg. Le chargeur ELF tourne-t-il sur le port $port ?"
    override fun serverLoaderNote(port: Int) = "Lancé via le chargeur ELF sur le port $port (modifiable dans l'onglet Payload)."
    override val setPs5InSettings = "Configurez d'abord votre PS5 dans Réglages."

    override val payloadIntro = "Envoyez n'importe quel payload .elf / .bin au chargeur de la PS5. PlayBeam Server se trouve dans Réglages."
    override val loaderPort = "Port du chargeur"
    override val loaderNote = "Le chargeur ELF écoute en général sur 9021 ; certains chargeurs .bin utilisent 9020."
    override val choosePayload = "Choisir un payload et l'envoyer"
    override fun sendingX(name: String) = "Envoi de $name…"
    override val recent = "Récents"
    override fun payloadSent(name: String, size: String, host: String, port: String) = "✅ $name envoyé ($size) à $host:$port"
    override fun payloadFailed(msg: String) = "$msg. Le chargeur de payload tourne-t-il sur la PS5 ?"
    override val setPs5OrFind = "Configurez d'abord votre PS5 dans Réglages (ou utilisez Trouver ma PS5)."

    override val history = "Historique"
    override val noUploads = "Aucun envoi pour l'instant"
    override val noUploadsSub = "Tout ce que vous envoyez à la PS5 apparaîtra ici."
    override fun historyLine(count: Int, size: String, dest: String) = "${files(count)} · $size → $dest"
    override val clearHistoryQ = "Effacer l'historique ?"
    override val clearHistoryBody = "L'historique des envois est supprimé de ce téléphone. Les fichiers sur la PS5 ne sont pas touchés."

    override val useThisFolder = "Utiliser ce dossier"
    override val extended = "Stockage étendu"
    override val stopped = "Arrêté"
    override fun alreadyExistsHere(name: String) = "« $name » existe déjà ici"
    override fun moving(name: String) = "Déplacement de $name"
    override fun moved(name: String) = "✅ $name déplacé"
    override fun movedTo(name: String, dir: String) = "✅ $name déplacé vers $dir"
    override fun copying(name: String) = "Copie de $name"
    override fun copied(name: String) = "✅ $name copié"
    override fun savedToPhone(name: String, size: String) = "✅ $name enregistré sur le téléphone ($size)"
    override val downloadStopped = "Téléchargement arrêté"
    override val ps5Files = "Fichiers PS5"
    override fun via(how: String) = "via $how"
    override fun downloading(name: String) = "Téléchargement de $name"
    override val emptyFolder = "Dossier vide"
    override val pasteHere = "Coller ici"
    override val newFolder = "Nouveau dossier"
    override fun deleteQ(name: String) = "Supprimer $name ?"
    override val deleteFolderBody = "Le dossier et tout son contenu seront supprimés de la PS5. Action irréversible."
    override val deleteFileBody = "Ce fichier sera supprimé de la PS5. Action irréversible."
    override fun free(size: String) = "$size libres"
    override fun ofTotal(done: String, total: String) = "$done sur $total"
    override val homebrewApps = "Applications homebrew"
    override val downloadToPhone = "Télécharger sur le téléphone"
    override val cut = "Couper"
    override val moveTo = "Déplacer vers…"
    override val details = "Détails"
    override fun moveX(name: String) = "Déplacer $name"
    override val toFolder = "Vers le dossier"
    override val thisFolder = "Ce dossier"
    override val ps5Homebrew = "homebrew PS5"
    override val usbHomebrew = "homebrew USB"
    override val moveNote = "Vers un autre disque, tout est copié puis l'original est supprimé."
    override val type = "Type"
    override val homebrewFolder = "Dossier homebrew"
    override val folder = "Dossier"
    override fun fileOfType(ext: String) = "Fichier $ext"
    override val file = "Fichier"
    override val location = "Emplacement"
    override val size = "Taille"
    override val sizeUnknown = "Inconnue (nécessite PlayBeam Server)"
    override val bytes = "octets"
    override val contains = "Contient"
    override fun containsLine(count: Int, dirs: Int) = "${files(count)}, $dirs dossiers"
    override val modified = "Modifié le"

    override val waitingNotif = "En attente…"
    override val scanning = "Analyse des fichiers…"
    override fun connectingTo(host: String) = "Connexion à $host…"
    override fun nWaiting(n: Int) = " • $n en attente"
    override val zipsNotExtracted = " — ZIP non décompressés (lancez PlayBeam Server pour cela)"
    override fun extractingPct(name: String, i: Int, n: Int, pct: Int) = "Décompression de $name ($i/$n) — $pct %"
    override fun extracting(name: String) = "Décompression de $name"
    override fun extractedNote(count: Int, zips: Int) = " — ${files(count)} extraits de $zips ZIP"
    override fun skippedNote(size: String) = " — $size étaient déjà sur la PS5"
    override val viaServer = " via PlayBeam Server"
    override fun uploadComplete(via: String, count: Int, size: String) = "✅ Envoi terminé$via : ${files(count)} ($size)"
    override val uploadStopped = "⏹ Envoi arrêté"
    override val chProgress = "Progression des envois"
    override val chDone = "Envoi terminé"
    override fun sentToPs5(label: String) = "$label envoyé vers la PS5"
    override fun notCompleted(label: String) = "$label — non terminé"

    override val secAppearance = "Apparence"
    override val themeSystem = "Système"
    override val themeLight = "Clair"
    override val themeDark = "Sombre"
    override val updates = "Mises à jour"
    override val checkUpdates = "Rechercher des mises à jour"
    override val autoCheck = "Vérifier automatiquement"
    override val autoCheckSub = "Chercher une nouvelle version à l'ouverture de l'app"
    override val checking = "Vérification…"
    override val upToDate = "Vous avez la dernière version"
    override val updateAvailable = "Mise à jour disponible"
    override fun updateVersions(new: String, current: String) = "La version $new est disponible (vous avez $current)."
    override val whatsNew = "Nouveautés"
    override val update = "Mettre à jour"
    override val later = "Plus tard"
    override val skipVersion = "Ignorer cette version"
    override val downloadingUpdate = "Téléchargement de la mise à jour…"
    override val allowInstall = "Autorisez PlayBeam à installer des applis, puis touchez à nouveau Mettre à jour."
    override val updateCheckFailed = "Impossible de vérifier les mises à jour"

    override val ps5Info = "Infos PS5"
    override val liveSensors = "Capteurs en direct"
    override val autoRefresh = "Mise à jour automatique"
    override val cpuTemp = "CPU"
    override val socTemp = "SoC"
    override val cpuFreq = "Fréquence CPU"
    override val uptime = "Allumée depuis"
    override fun firmware(fw: String) = "Firmware $fw"
    override val storage = "Stockage"
    override val internalStorage = "PS5 (interne)"
    override fun usedOf(used: String, total: String) = "$used utilisés sur $total"
    override val infoNeedsServer = "Lancez PlayBeam Server pour voir les détails, températures et stockage de votre PS5."
    override val appTagline = "Envoyez fichiers et dossiers de votre téléphone vers votre PS5 jailbreakée — rapide, simple et gratuit."
    override val openSource = "Open source"
    override val madeBy = "Créé par youss17x"

    override fun discordCopied(user: String) = "Pseudo Discord « $user » copié"
    override val notOnWifi = "Votre téléphone n'est pas en Wi-Fi. Connectez-le au même Wi-Fi que la PS5."
    override val serverErrorGeneric = "Erreur de PlayBeam Server"
    override fun cantReach(host: String) = "Impossible de joindre la PS5 à $host. Est-elle allumée, avec PlayBeam Server (ou FTP) lancé ?"
    override val somethingWrong = "Un problème est survenu"
}

object Ar : Strings() {
    override val cancel = "إلغاء"
    override val done = "تم"
    override val save = "حفظ"
    override val close = "إغلاق"
    override val stop = "إيقاف"
    override val name = "الاسم"
    override val notSet = "غير محدد"
    override val choose = "اختيار"
    override val change = "تغيير"
    override val delete = "حذف"
    override val clear = "مسح"
    override val send = "إرسال"
    override val create = "إنشاء"
    override val rename = "إعادة تسمية"
    override val move = "نقل"
    override val copy = "نسخ"

    override val tabUpload = "رفع"
    override val tabFiles = "الملفات"
    override val tabPayload = "Payload"
    override val tabHistory = "السجل"
    override val tabSettings = "الإعدادات"

    override val noPs5Title = "لم تتم إضافة PS5 بعد"
    override val noPs5Body = "أضف جهاز PS5 من الإعدادات لتتصفح ملفاته."
    override val openSettings = "فتح الإعدادات"
    override val uploadHere = "ارفع هنا"

    override fun files(n: Int) = when {
        n == 1 -> "ملف واحد"
        n == 2 -> "ملفان"
        n in 3..10 -> "$n ملفات"
        else -> "$n ملف"
    }
    override val oneFile = "ملف واحد"
    override val chipSending = "جارٍ الإرسال"
    override val chipNoPs5 = "لا يوجد PS5"
    override val sendToPs5 = "أرسل إلى PS5"
    override val chooseWhatTitle = "اختر ما تريد إرساله"
    override val chooseWhatSub = "مجلد أو ملفات من هاتفك"
    override val folderReady = "المجلد جاهز للإرسال"
    override val oneFileReady = "ملف واحد جاهز للإرسال"
    override val readyToSend = "جاهز للإرسال"
    override val console = "الجهاز"
    override val destination = "الوجهة"
    override val transfer = "طريقة النقل"
    override val transferFastSub = "الأسرع — الخادم يعمل على PS5"
    override val transferFtpSub = "شغّل PlayBeam Server من الإعدادات لرفع أسرع"
    override val optResume = "استئناف"
    override fun optConn(n: Int) = "$n اتصال"
    override val optUnzip = "فك الضغط"
    override val options = "الخيارات"
    override val addToQueue = "أضف إلى الانتظار"
    override val uploadToPs5 = "ارفع إلى PS5"
    override val setPs5First = "حدد جهاز PS5 أولاً: اضغط على الجهاز."
    override val nowSending = "يُرسل الآن"
    override val lastUpload = "آخر رفع"
    override fun progressLine(pct: Int, done: String, total: String) = "$pct% · $done من $total"
    override fun filesOf(done: Int, total: Int) = "$done / $total ملف"
    override val stopAll = "إيقاف الكل"
    override fun waiting(n: Int) = "في الانتظار · $n"
    override val whatToSend = "ماذا تريد أن ترسل؟"
    override val aFolder = "مجلد"
    override val aFolderSub = "كل ما بداخله مع المجلدات الفرعية"
    override val filesOpt = "ملفات"
    override val filesOptSub = "اختر ملفاً أو عدة ملفات"
    override val pathOnPs5 = "المسار على PS5"
    override val quickPicks = "اختيارات سريعة"
    override val browsePs5 = "تصفح PS5"
    override val presetPs5 = "PS5 · homebrew"
    override val presetUsb = "USB · homebrew"

    override fun searching(pct: Int) = "جارٍ البحث… $pct%"
    override val findMyPs5 = "ابحث عن جهازي"
    override val searchFailed = "فشل البحث"
    override val noPs5Found = "لم يُعثر على PS5. تأكد أن PlayBeam Server أو خادم FTP يعمل على الجهاز، وأن الهاتف والـ PS5 على نفس شبكة الواي فاي."
    override val ps5Ip = "عنوان IP للـ PS5"
    override val ftpPort = "منفذ FTP"
    override fun portNote(port: Int) = "PlayBeam Server يستخدم المنفذ $port تلقائياً دائماً. منفذ FTP يُستخدم فقط عندما لا يعمل الخادم (عادة 2121)."
    override val myPs5 = "جهازي PS5"
    override fun ps5N(n: Int) = "PS5 $n"
    override val saveThisPs5 = "احفظ هذا الجهاز"

    override val settings = "الإعدادات"
    override val secPs5 = "PS5"
    override val secServer = "PlayBeam Server"
    override val secTransfers = "النقل"
    override val resumeTitle = "استئناف الملفات غير المكتملة"
    override val resumeSub = "يتخطى الملفات الموجودة على PS5 ويكمل الملفات الناقصة"
    override val extractTitle = "فك ضغط ZIP على PS5"
    override val extractSub = "يفك ضغط ملفات zip المرفوعة ثم يحذفها (يحتاج PlayBeam Server)"
    override val connections = "عدد الاتصالات"
    override val connectionsSub = "الأكثر أسرع مع الملفات الصغيرة الكثيرة"
    override val secDownloads = "التنزيلات"
    override val downloadFolder = "مجلد التنزيل في الهاتف"
    override val notChosen = "لم يُختر"
    override val downloadFolderSub = "مكان حفظ الملفات المنزّلة من PS5"
    override val secAbout = "حول التطبيق"
    override val version = "الإصدار"
    override val serverPort = "منفذ PlayBeam Server"
    override val sourceCode = "الكود المصدري"
    override val secLanguage = "اللغة"

    override val serverCardSub = "رفع أسرع · يفك ضغط ZIP على PS5"
    override val running = "يعمل"
    override val off = "متوقف"
    override val startOnPs5 = "تشغيل على PS5"
    override val stopServer = "إيقاف الخادم"
    override val sendingServer = "جارٍ إرسال PlayBeam Server…"
    override val starting = "جارٍ التشغيل…"
    override val stopping = "جارٍ الإيقاف…"
    override val serverRunningMsg = "✅ PlayBeam Server يعمل — الرفع يستخدمه الآن تلقائياً"
    override val serverNoAnswer = "تم الإرسال لكن الخادم لم يرد. راجع إشعارات PS5."
    override val serverStopped = "تم إيقاف PlayBeam Server"
    override val sendFailed = "فشل الإرسال"
    override fun loaderNotRunning(msg: String, port: Int) = "$msg. هل الـ ELF loader يعمل على المنفذ $port؟"
    override fun serverLoaderNote(port: Int) = "يُشغَّل عبر الـ ELF loader على المنفذ $port (يمكن تغييره من تبويب Payload)."
    override val setPs5InSettings = "حدد جهاز PS5 أولاً من الإعدادات."

    override val payloadIntro = "أرسل أي payload بصيغة ‎.elf‎ أو ‎.bin‎ إلى الـ loader في PS5. ستجد PlayBeam Server في الإعدادات."
    override val loaderPort = "منفذ الـ loader"
    override val loaderNote = "الـ ELF loader يستمع عادة على 9021، وبعض loaders الخاصة بـ ‎.bin‎ تستخدم 9020."
    override val choosePayload = "اختر payload وأرسله"
    override fun sendingX(name: String) = "جارٍ إرسال $name…"
    override val recent = "الأخيرة"
    override fun payloadSent(name: String, size: String, host: String, port: String) = "✅ تم إرسال $name ($size) إلى $host:$port"
    override fun payloadFailed(msg: String) = "$msg. هل الـ payload loader يعمل على PS5؟"
    override val setPs5OrFind = "حدد جهاز PS5 أولاً من الإعدادات (أو استخدم ابحث عن جهازي)."

    override val history = "السجل"
    override val noUploads = "لا توجد عمليات رفع بعد"
    override val noUploadsSub = "كل ما ترسله إلى PS5 سيظهر هنا."
    override fun historyLine(count: Int, size: String, dest: String) = "${files(count)} · $size ← $dest"
    override val clearHistoryQ = "مسح السجل؟"
    override val clearHistoryBody = "سيُحذف سجل الرفع من هذا الهاتف فقط، ولن تُمس الملفات على PS5."

    override val useThisFolder = "استخدم هذا المجلد"
    override val extended = "التخزين الإضافي"
    override val stopped = "تم الإيقاف"
    override fun alreadyExistsHere(name: String) = "«$name» موجود هنا مسبقاً"
    override fun moving(name: String) = "جارٍ نقل $name"
    override fun moved(name: String) = "✅ تم نقل $name"
    override fun movedTo(name: String, dir: String) = "✅ تم نقل $name إلى $dir"
    override fun copying(name: String) = "جارٍ نسخ $name"
    override fun copied(name: String) = "✅ تم نسخ $name"
    override fun savedToPhone(name: String, size: String) = "✅ تم حفظ $name في هاتفك ($size)"
    override val downloadStopped = "تم إيقاف التنزيل"
    override val ps5Files = "ملفات PS5"
    override fun via(how: String) = "عبر $how"
    override fun downloading(name: String) = "جارٍ تنزيل $name"
    override val emptyFolder = "مجلد فارغ"
    override val pasteHere = "ألصق هنا"
    override val newFolder = "مجلد جديد"
    override fun deleteQ(name: String) = "حذف $name؟"
    override val deleteFolderBody = "سيُحذف المجلد وكل ما بداخله من PS5. لا يمكن التراجع عن ذلك."
    override val deleteFileBody = "سيُحذف هذا الملف من PS5. لا يمكن التراجع عن ذلك."
    override fun free(size: String) = "$size متاحة"
    override fun ofTotal(done: String, total: String) = "$done من $total"
    override val homebrewApps = "تطبيقات homebrew"
    override val downloadToPhone = "تنزيل إلى الهاتف"
    override val cut = "قص"
    override val moveTo = "نقل إلى…"
    override val details = "التفاصيل"
    override fun moveX(name: String) = "نقل $name"
    override val toFolder = "إلى المجلد"
    override val thisFolder = "هذا المجلد"
    override val ps5Homebrew = "homebrew على PS5"
    override val usbHomebrew = "homebrew على USB"
    override val moveNote = "النقل إلى قرص آخر ينسخ كل شيء ثم يحذف الأصل."
    override val type = "النوع"
    override val homebrewFolder = "مجلد homebrew"
    override val folder = "مجلد"
    override fun fileOfType(ext: String) = "ملف $ext"
    override val file = "ملف"
    override val location = "المكان"
    override val size = "الحجم"
    override val sizeUnknown = "غير معروف (يحتاج PlayBeam Server)"
    override val bytes = "بايت"
    override val contains = "يحتوي على"
    override fun containsLine(count: Int, dirs: Int) = "${files(count)}، $dirs مجلد"
    override val modified = "آخر تعديل"

    override val waitingNotif = "في الانتظار…"
    override val scanning = "جارٍ فحص الملفات…"
    override fun connectingTo(host: String) = "جارٍ الاتصال بـ $host…"
    override fun nWaiting(n: Int) = " • $n في الانتظار"
    override val zipsNotExtracted = " — لم يُفك ضغط ZIP (شغّل PlayBeam Server لذلك)"
    override fun extractingPct(name: String, i: Int, n: Int, pct: Int) = "جارٍ فك ضغط $name ($i/$n) — $pct%"
    override fun extracting(name: String) = "جارٍ فك ضغط $name"
    override fun extractedNote(count: Int, zips: Int) = " — فُك ضغط ${files(count)} من $zips ZIP"
    override fun skippedNote(size: String) = " — $size كانت موجودة مسبقاً على PS5"
    override val viaServer = " عبر PlayBeam Server"
    override fun uploadComplete(via: String, count: Int, size: String) = "✅ اكتمل الرفع$via: ${files(count)} ($size)"
    override val uploadStopped = "⏹ تم إيقاف الرفع"
    override val chProgress = "تقدّم الرفع"
    override val chDone = "انتهاء الرفع"
    override fun sentToPs5(label: String) = "تم إرسال $label إلى PS5"
    override fun notCompleted(label: String) = "$label — لم يكتمل"

    override val secAppearance = "المظهر"
    override val themeSystem = "حسب النظام"
    override val themeLight = "فاتح"
    override val themeDark = "داكن"
    override val updates = "التحديثات"
    override val checkUpdates = "البحث عن تحديث"
    override val autoCheck = "البحث تلقائياً"
    override val autoCheckSub = "يبحث عن إصدار جديد عند فتح التطبيق"
    override val checking = "جارٍ البحث…"
    override val upToDate = "لديك أحدث إصدار"
    override val updateAvailable = "يوجد تحديث جديد"
    override fun updateVersions(new: String, current: String) = "الإصدار $new متوفر (لديك $current)."
    override val whatsNew = "ما الجديد"
    override val update = "تحديث"
    override val later = "لاحقاً"
    override val skipVersion = "تخطي هذا الإصدار"
    override val downloadingUpdate = "جارٍ تنزيل التحديث…"
    override val allowInstall = "اسمح لـ PlayBeam بتثبيت التطبيقات، ثم اضغط تحديث مرة أخرى."
    override val updateCheckFailed = "تعذّر البحث عن تحديث"

    override val ps5Info = "معلومات PS5"
    override val liveSensors = "المستشعرات المباشرة"
    override val autoRefresh = "تتحدث تلقائياً"
    override val cpuTemp = "المعالج"
    override val socTemp = "الشريحة SoC"
    override val cpuFreq = "تردد المعالج"
    override val uptime = "مدة التشغيل"
    override fun firmware(fw: String) = "الإصدار $fw"
    override val storage = "التخزين"
    override val internalStorage = "PS5 (داخلي)"
    override fun usedOf(used: String, total: String) = "$used مستخدمة من $total"
    override val infoNeedsServer = "شغّل PlayBeam Server لترى تفاصيل جهازك ودرجات الحرارة والمساحة."
    override val appTagline = "أرسل الملفات والمجلدات من هاتفك إلى PS5 المعدّل — بسرعة وسهولة ومجاناً."
    override val openSource = "مفتوح المصدر"
    override val madeBy = "من تطوير youss17x"

    override fun discordCopied(user: String) = "تم نسخ اسم Discord «$user»"
    override val notOnWifi = "هاتفك غير متصل بالواي فاي. صِله بنفس شبكة الواي فاي التي عليها PS5."
    override val serverErrorGeneric = "خطأ في PlayBeam Server"
    override fun cantReach(host: String) = "تعذّر الوصول إلى PS5 على $host. هل الجهاز يعمل، وهل PlayBeam Server (أو FTP) مشغّل؟"
    override val somethingWrong = "حدث خطأ ما"
}
