package code.name.monkey.retromusic.fragments.room

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.RoomRole
import code.name.monkey.retromusic.SyncController
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import org.koin.android.ext.android.inject

class RoomControlSheet : BottomSheetDialogFragment() {

    private val TAG = "RoomControlSheet"
    private val syncController: SyncController by inject()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_room_control, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val state = syncController.roomState.value
        Log.d(TAG, "onViewCreated: role=${state.role} host=${state.hostNickname}")

        val tvTitle = view.findViewById<TextView>(R.id.tv_room_title)
        val tvRole = view.findViewById<TextView>(R.id.tv_room_role)
        val layoutHost = view.findViewById<View>(R.id.layout_host_controls)
        val layoutFollower = view.findViewById<View>(R.id.layout_follower_controls)

        when (state.role) {
            RoomRole.HOSTING -> {
                tvTitle.text = "Room Controls"
                tvRole.text = "You are hosting"
                layoutHost.visibility = View.VISIBLE
                layoutFollower.visibility = View.GONE

                val switchGuestControl = view.findViewById<Switch>(R.id.switch_guest_control)
                switchGuestControl.isChecked = state.allowGuestControl
                switchGuestControl.setOnCheckedChangeListener { _, checked ->
                    Log.i(TAG, "guest control toggled: $checked")
                    syncController.setGuestControl(checked)
                }

                view.findViewById<MaterialButton>(R.id.btn_sync_all).setOnClickListener {
                    Log.i(TAG, "sync all pressed")
                    syncController.syncAll()
                    dismiss()
                }

                view.findViewById<MaterialButton>(R.id.btn_dissolve_room).setOnClickListener {
                    Log.i(TAG, "dissolve room pressed")
                    syncController.leaveRoom()
                    dismiss()
                }
            }
            RoomRole.FOLLOWING -> {
                tvTitle.text = "Room Controls"
                tvRole.text = "Following: ${state.hostNickname ?: "someone"}"
                layoutHost.visibility = View.GONE
                layoutFollower.visibility = View.VISIBLE

                view.findViewById<MaterialButton>(R.id.btn_catch_up).setOnClickListener {
                    Log.i(TAG, "catch up pressed")
                    syncController.catchUp()
                    dismiss()
                }

                view.findViewById<MaterialButton>(R.id.btn_leave_room).setOnClickListener {
                    Log.i(TAG, "leave room pressed")
                    syncController.leaveRoom()
                    dismiss()
                }
            }
            RoomRole.SOLO -> {
                tvTitle.text = "Room Controls"
                tvRole.text = "Not in a room"
                layoutHost.visibility = View.GONE
                layoutFollower.visibility = View.GONE
            }
        }
    }

    companion object {
        fun newInstance() = RoomControlSheet()
    }
}
