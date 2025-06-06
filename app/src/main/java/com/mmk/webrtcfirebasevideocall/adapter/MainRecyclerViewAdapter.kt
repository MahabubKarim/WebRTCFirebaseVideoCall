package com.mmk.webrtcfirebasevideocall.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.mmk.webrtcfirebasevideocall.R
import com.mmk.webrtcfirebasevideocall.databinding.ItemMainRecyclerViewBinding

class MainRecyclerViewAdapter(private val listener: Listener) :
    RecyclerView.Adapter<MainRecyclerViewAdapter.MainRecyclerViewHolder>() {

    private var usersList: List<Pair<String, String>>? = null

    @SuppressLint("NotifyDataSetChanged")
    fun updateList(list: List<Pair<String, String>>) {
        this.usersList = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainRecyclerViewHolder {
        val binding = ItemMainRecyclerViewBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MainRecyclerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MainRecyclerViewHolder, position: Int) {
        usersList?.let { list ->
            val user = list[position]
            holder.bind(user, {
                listener.onVideoCallClicked(it)
            }, {
                listener.onAudioCallClicked(it)
            })
        }
    }

    override fun getItemCount(): Int {
        return usersList?.size ?: 0
    }

    interface Listener {
        fun onVideoCallClicked(username: String)
        fun onAudioCallClicked(username: String)
    }

    class MainRecyclerViewHolder(private val binding: ItemMainRecyclerViewBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val context = binding.root.context

        fun bind(
            user: Pair<String, String>,
            videoCallClicked: (String) -> Unit,
            audioCallClicked: (String) -> Unit
        ) {
            binding.apply {
                when (user.second) {
                    "ONLINE" -> {
                        videoCallBtn.isVisible = true
                        audioCallBtn.isVisible = true
                        videoCallBtn.setOnClickListener {
                            videoCallClicked.invoke(user.first)
                        }
                        audioCallBtn.setOnClickListener {
                            audioCallClicked.invoke(user.first)
                        }
                        statusTv.setTextColor(context.resources.getColor(R.color.light_green, null))
                        statusTv.text = context.getString(R.string.online)
                    }

                    "OFFLINE" -> {
                        videoCallBtn.isVisible = false
                        audioCallBtn.isVisible = false
                        statusTv.setTextColor(context.resources.getColor(R.color.red, null))
                        statusTv.text = context.getString(R.string.offline)
                    }

                    "IN_CALL" -> {
                        videoCallBtn.isVisible = false
                        audioCallBtn.isVisible = false
                        statusTv.setTextColor(context.resources.getColor(R.color.yellow, null))
                        statusTv.text = context.getString(R.string.in_call)
                    }
                }
                usernameTv.text = user.first
            }
        }
    }
}