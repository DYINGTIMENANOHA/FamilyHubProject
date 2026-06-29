package code.name.monkey.retromusic.fragments.friends

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.FriendRequestInfo
import code.name.monkey.retromusic.R
import com.google.android.material.button.MaterialButton
import org.koin.androidx.viewmodel.ext.android.sharedViewModel

class FriendRequestsFragment : Fragment() {

    private val TAG = "FriendReqFragment"
    private val viewModel: FriendsViewModel by sharedViewModel()

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private val adapter = RequestsAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_friend_requests, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")

        recyclerView = view.findViewById(R.id.rv_requests)
        emptyText = view.findViewById(R.id.tv_no_requests)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.onAccept = { req ->
            Log.i(TAG, "accept: id=${req.id} from=${req.from_nickname}")
            viewModel.acceptRequest(req.id)
        }
        adapter.onReject = { req ->
            Log.i(TAG, "reject: id=${req.id} from=${req.from_nickname}")
            viewModel.rejectRequest(req.id)
        }

        viewModel.pendingRequests.observe(viewLifecycleOwner) { list ->
            Log.d(TAG, "requests updated: count=${list.size}")
            adapter.submitList(list)
            emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.loadPendingRequests()
    }
}

// ─── Inline Adapter ───────────────────────────────────────────────────────────

private class RequestsAdapter : RecyclerView.Adapter<RequestsAdapter.VH>() {

    var onAccept: ((FriendRequestInfo) -> Unit)? = null
    var onReject: ((FriendRequestInfo) -> Unit)? = null
    private val items = mutableListOf<FriendRequestInfo>()

    fun submitList(list: List<FriendRequestInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_friend_request, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFrom: TextView = itemView.findViewById(R.id.tv_request_from)
        private val btnAccept: MaterialButton = itemView.findViewById(R.id.btn_accept)
        private val btnReject: MaterialButton = itemView.findViewById(R.id.btn_reject)

        fun bind(req: FriendRequestInfo) {
            tvFrom.text = req.from_nickname
            btnAccept.setOnClickListener { onAccept?.invoke(req) }
            btnReject.setOnClickListener { onReject?.invoke(req) }
        }
    }
}
