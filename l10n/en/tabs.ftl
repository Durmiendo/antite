tabs-all = All
tabs-incognito = Incognito
tabs-empty = No tabs here yet
tabs-moved-to = Tab moved to "{ $name }"
tabs-moved-out = Tab taken out of its category
tabs-count-total = Total number of tabs on the right
tabs-count-each = Number of tabs on each category
settings-categories = Categories
categories-mine = My categories
categories-summary = { $count ->
    [0] No own categories
    [one] { $count } category
   *[other] { $count } categories
}
categories-add = Add a category
categories-note = A new tab opens in the category open in the tab switcher, a link in the category of its page. A pin on the new tab page can be tied to a category: it then opens there.
category-tabs = { $count ->
    [0] No tabs
    [one] { $count } tab
   *[other] { $count } tabs
}
category-name = Name
category-color = Color
category-icon = Icon
category-icon-pick = Pick a picture
category-icon-replace = Replace the picture
category-icon-formats = SVG, PNG, JPG or WebP
category-icon-remove = Remove the picture
category-icon-bad = Could not read the picture
category-place = Place in the list
category-delete = Delete the category
category-delete-confirm = Delete the category “{ $name }”? Its tabs stay in “All”.
incognito-summary = Screenshots, closing on exit, unlock
incognito-secure = Block screenshots
incognito-secure-summary = Incognito tabs stay out of screenshots, screen recordings and the recent apps list
incognito-lock = Unlock to enter
incognito-lock-summary = Incognito tabs show only after a fingerprint or PIN; leaving the browser locks them again
incognito-locked = Incognito tabs are locked
incognito-unlock = Unlock
incognito-unlock-reason = Open incognito tabs
incognito-show-tabs = Open the tab list
ntp-pin-category = Category: { $name }
category-locked = Category "{ $name }" is locked
category-unlock-reason = Open category "{ $name }"
category-lock = Lock
category-lock-none = No lock
category-lock-none-summary = The category's tabs and history are visible to anyone holding the open browser
category-lock-device = Phone unlock
category-lock-device-summary = Opens with what opens the phone: PIN, pattern, password or fingerprint. Whoever knows the phone's PIN opens the category too. Removing the screen lock loses the category's history
category-lock-password = Category password
category-lock-password-summary = A password of its own that only you know. Forget it and the category's history is gone
category-lock-both = Password and phone
category-lock-both-summary = Both are needed: the phone's PIN without the password does not help, nor the password without this phone
category-password-new = Category password
category-password-new-warning = A forgotten password cannot be recovered: the category's history goes with it
category-protect-downloads = Protect downloaded files
category-protect-downloads-summary = Files from these tabs go straight to the secure storage and do not show in downloads
containers-title = Containers
containers-summary = { $count ->
    [0] Separate cookies and site logins
   *[other] Containers: { $count }
}
containers-add = Add a container
containers-note = Tabs of one container share cookies and site data only with each other: in different containers one site can be signed in under different names. A page opens in a container from the menu, "Open in container…", or through a category that has one chosen. Passwords, history and site permissions are common.
container-none = No container
container-in = Container "{ $name }"
container-delete = Delete container
container-delete-confirm = Delete container "{ $name }"? Its tabs are closed, and its cookies and site data are wiped.
category-container = Container for new tabs
menu-open-in-container = Open in container…
