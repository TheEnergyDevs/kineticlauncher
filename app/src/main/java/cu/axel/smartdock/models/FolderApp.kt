package cu.axel.smartdock.models

import android.graphics.drawable.Drawable

class FolderApp(
    val folderName: String,
    val apps: ArrayList<App>,
    icon: Drawable,
) : App(folderName, "folder.$folderName", icon)
