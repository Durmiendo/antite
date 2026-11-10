updates-checking = Checking…
updates-check-failed = Could not check for updates
updates-none = No updates
updates-engine-confirm = Gecko { $version } is available from “{ $source }”, { $size } MB. Download?
updates-downloading = Downloading…
updates-downloading-percent = Downloading… { $percent }%
updates-rollback-confirm = Go back to Gecko { $previous }? Gecko { $active } will not be offered again. The browser restarts.
updates-rollback-action = Go back
updates-restart = Restart the browser
updates-restart-summary = Gecko { $version } is downloaded and starts working after a restart
updates-shell = Update { -brand }
updates-shell-summary = Build { $version } is available. The system will ask to confirm the installation
updates-never-checked = Not checked yet
updates-last-check = Last check: { $time }
updates-last-available = , Gecko { $version } is available
updates-last-shell = , a new { -brand } version is available
updates-last-error = , error: { $reason }
updates-last-none = , no updates
updates-check-now = Check now
updates-how = How to update
updates-auto = Check automatically
updates-how-often = How often
updates-install = Installation
updates-wifi-only = Download over Wi-Fi only
updates-wifi-only-summary = About 95 MB per update
updates-source = Update source
updates-sources = Sources
updates-installed = Installed
updates-shell-build = Build { $code }
updates-running = Running{ $origin }
updates-after-restart = After a restart{ $origin }
updates-previous = Previous{ $origin }. Tap to go back to it
updates-rollback-note = If a new engine fails to start { $count } times in a row, the browser goes back to the previous one by itself
updates-debug = Debug
updates-debug-mode = Debug mode
updates-debug-mode-summary = Install the engine and { -brand } from any address, http too, without a signature check
updates-debug-warning = Without a signature check any code can be installed. It runs inside the browser with access to everything, passwords too. Turn it on only for your own builds.
updates-debug-enable = Turn on
updates-debug-enable-auth = Turn on debug mode
updates-debug-engine = Gecko engine { $version }, { $size } MB
updates-debug-shell-downloading = Downloading { -brand }…
updates-debug-shell-downloading-percent = Downloading { -brand }… { $percent }%
updates-debug-engine-downloading-percent = Downloading the engine… { $percent }%
updates-debug-nothing = This address has neither an engine for this version nor an { -brand } build
updates-debug-install = Install
updates-debug-from-address = Install from an address
updates-debug-from-address-summary = The engines.json address, for example http://192.168.1.5:8790/engines.json
updates-debug-index-address = engines.json address
updates-debug-check = Check
updates-debug-failed = Failed: { $reason }
updates-check-off = checks are off
updates-check-interval = checks { $interval }
updates-source-confirm =
    Engine and { -brand } updates will come only from “{ $source }”.

    SHA-256 key fingerprint:
    { $fingerprint }
updates-source-change = Change
sources-add = Add a source
sources-add-warning = A source can install code that runs in the browser
sources-fingerprint-confirm =
    SHA-256 key fingerprint:
    { $fingerprint }

    Compare it with the fingerprint the publisher has published. Add the source?
sources-note = A source is the address of an engine list and its publisher's key. An engine is installed only if the list is signed with that key. Updates come only from the update source; the other sources can be checked by hand on their page
sources-disabled = Off
