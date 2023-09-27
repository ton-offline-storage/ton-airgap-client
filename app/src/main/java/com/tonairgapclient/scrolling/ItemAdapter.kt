package com.tonairgapclient.scrolling

import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

sealed class ItemAdapter<VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
    fun registerEmptyObserver(emptyPlaceholder: TextView) {
        registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                private fun checkEmpty() {
                    Log.d("Debug", "Checker worked")
                    emptyPlaceholder.visibility = if(itemCount == 0) View.VISIBLE else View.GONE
                }
                override fun onChanged() {
                    Log.d("Debug", "Change detected")
                    super.onChanged()
                    checkEmpty()
                }
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                    super.onItemRangeChanged(positionStart, itemCount)
                    checkEmpty()
                }
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                    super.onItemRangeChanged(positionStart, itemCount, payload)
                    checkEmpty()
                }
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    super.onItemRangeInserted(positionStart, itemCount)
                    checkEmpty()
                }
                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                    super.onItemRangeMoved(fromPosition, toPosition, itemCount)
                    checkEmpty()
                }
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    super.onItemRangeRemoved(positionStart, itemCount)
                    checkEmpty()
                }
            }
        )
    }
}