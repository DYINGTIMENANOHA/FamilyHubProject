package code.name.monkey.retromusic.fragments.familyhub

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.CinemaActivity
import code.name.monkey.retromusic.activities.LivestreamActivity
import com.google.android.material.button.MaterialButton

class FamilyHubCinemaFragment : Fragment(R.layout.fragment_familyhub_feature) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.familyhub_feature_title).setText(R.string.familyhub_cinema)
        view.findViewById<TextView>(R.id.familyhub_feature_status).setText(R.string.familyhub_cinema_choose_status)
        view.findViewById<MaterialButton>(R.id.familyhub_feature_action).apply {
            visibility = View.VISIBLE
            text = getString(R.string.familyhub_cinema_watch)
        }.setOnClickListener {
            startActivity(CinemaActivity.intent(requireContext(), admin = false))
        }
        view.findViewById<MaterialButton>(R.id.familyhub_feature_secondary_action).apply {
            visibility = View.VISIBLE
            text = getString(R.string.familyhub_cinema_admin)
        }.setOnClickListener {
            startActivity(CinemaActivity.intent(requireContext(), admin = true))
        }
    }
}

class FamilyHubMoreFragment : Fragment(R.layout.fragment_familyhub_feature) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.familyhub_feature_title).setText(R.string.familyhub_livestream)
        view.findViewById<TextView>(R.id.familyhub_feature_status).setText(R.string.familyhub_livestream_status)
        view.findViewById<MaterialButton>(R.id.familyhub_feature_action).apply {
            visibility = View.VISIBLE
            text = getString(R.string.familyhub_open)
        }.setOnClickListener {
            startActivity(LivestreamActivity.intent(requireContext()))
        }
    }
}

abstract class FamilyHubFeatureFragment(
    @StringRes private val titleRes: Int,
    @StringRes private val statusRes: Int,
) : Fragment(R.layout.fragment_familyhub_feature) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.familyhub_feature_title).setText(titleRes)
        view.findViewById<TextView>(R.id.familyhub_feature_status).setText(statusRes)
    }
}
