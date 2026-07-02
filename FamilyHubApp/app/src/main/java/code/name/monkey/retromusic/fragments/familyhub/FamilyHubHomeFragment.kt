package code.name.monkey.retromusic.fragments.familyhub

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SyncTuneSession
import code.name.monkey.retromusic.activities.CloudMusicActivity
import com.google.android.material.button.MaterialButton

class FamilyHubHomeFragment : Fragment(R.layout.fragment_familyhub_home) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.familyhub_user_name).text = SyncTuneSession.nickname ?: "FamilyHub"
        view.findViewById<TextView>(R.id.familyhub_device_id).text = SyncTuneSession.deviceId
        view.findViewById<MaterialButton>(R.id.familyhub_cloud_music_action).setOnClickListener {
            startActivity(CloudMusicActivity.intent(requireContext()))
        }
    }
}
