package com.tonairgapclient.scrolling

import android.util.Log
import androidx.recyclerview.selection.SelectionTracker
import com.tonairgapclient.activities.WatchAccountsActivity

class SelectionDetector(private val tracker: SelectionTracker<Long>,
                        private val activity: WatchAccountsActivity,
                        private val adapter: AccountItemAdapter
) : SelectionTracker.SelectionObserver<Long>() {
    private var isSelected: Boolean = false
    override fun onSelectionChanged() {
        super.onSelectionChanged()
        if(!isSelected && tracker.hasSelection()) {
            Log.d("Debug","Selection start detected")
            isSelected = true
            activity.enterDeletion()
        }
        if(isSelected && !tracker.hasSelection()) {
            Log.d("Debug","User deselection detected")
            isSelected = false
            //adapter.notifyItemRangeChanged(0, adapter.itemCount)
            activity.leaveDeletion()
        }
    }
    override fun onItemStateChanged(key: Long, selected: Boolean) {
        super.onItemStateChanged(key, selected)
        if(!selected) {
            Log.d("Debug", "Item $key deselected")
            adapter.notifyItemChanged(key.toInt())
        } else {
            Log.d("Debug", "Item $key selected")
        }
    }
    fun isSelected(): Boolean = isSelected
    fun deselect() {
        Log.d("Debug", "Auto deselection detected")
        tracker.clearSelection()
        isSelected = false
    }
    fun selected() = tracker.selection
}