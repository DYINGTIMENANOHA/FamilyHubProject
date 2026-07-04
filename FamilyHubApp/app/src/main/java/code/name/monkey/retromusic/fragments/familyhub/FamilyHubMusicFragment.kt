package code.name.monkey.retromusic.fragments.familyhub

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import code.name.monkey.retromusic.EXTRA_PLAYLIST_TYPE
import code.name.monkey.retromusic.FAVOURITES
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.CloudMusicActivity
import com.google.android.material.button.MaterialButton

class FamilyHubMusicFragment : Fragment(R.layout.fragment_familyhub_music) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.familyhub_music_cloud_action).setOnClickListener {
            startActivity(CloudMusicActivity.intent(requireContext()))
        }
        view.findViewById<MaterialButton>(R.id.familyhub_music_local_action).setOnClickListener {
            findNavController().navigate(R.id.action_home)
        }
        view.findViewById<MaterialButton>(R.id.familyhub_music_local_songs).setOnClickListener {
            findNavController().navigate(R.id.action_song)
        }
        view.findViewById<MaterialButton>(R.id.familyhub_music_local_favorites).setOnClickListener {
            findNavController().navigate(
                R.id.detailListFragment,
                bundleOf(EXTRA_PLAYLIST_TYPE to FAVOURITES)
            )
        }
        view.findViewById<MaterialButton>(R.id.familyhub_music_local_playlists).setOnClickListener {
            findNavController().navigate(R.id.action_playlist)
        }
    }
}
