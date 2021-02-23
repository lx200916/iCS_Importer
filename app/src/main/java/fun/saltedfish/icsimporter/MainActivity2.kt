package `fun`.saltedfish.icsimporter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

class MainActivity2 : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.action
        if (Intent.ACTION_SEND == action) {
            (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let {
                val uri = it
                val file = contentResolver.openInputStream(uri) ?: return

                Toast.makeText(this, uri.toString(), Toast.LENGTH_LONG).show()
                Toast.makeText(
                    this,
                    DocumentFile.fromSingleUri(this, uri)?.name ?: "",
                    Toast.LENGTH_LONG
                ).show()
                Toast.makeText(
                    this,
                    DocumentFile.fromSingleUri(this, uri)?.length()?.div(1024.0).toString() ?: "",
                    Toast.LENGTH_LONG
                ).show()


            }
        }

    }
}