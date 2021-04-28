package eu.kanade.tachiyomi.widget

import android.app.Activity
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.view.updateLayoutParams

/**
 * Edge to Edge BottomSheetDiolag that uses a custom theme and settings to extend pass the nav bar
 */
@Suppress("LeakingThis")
abstract class E2EBottomSheetDialog<VB : ViewBinding>(activity: Activity) :
    BottomSheetDialog(activity, R.style.BottomSheetDialogTheme) {
    protected val binding: VB

    protected val sheetBehavior: BottomSheetBehavior<*>
    protected open var recyclerView: RecyclerView? = null

    init {
        binding = createBinding(activity.layoutInflater)
        setContentView(binding.root)

        sheetBehavior = BottomSheetBehavior.from(binding.root.parent as ViewGroup)

        val contentView = binding.root

        window?.setBackgroundDrawable(null)
        window?.navigationBarColor = activity.window.navigationBarColor
        val isLight = (activity.window?.decorView?.systemUiVisibility ?: 0) and View
            .SYSTEM_UI_FLAG_LIGHT_STATUS_BAR == View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isLight) {
            window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window?.findViewById<View>(com.google.android.material.R.id.container)?.fitsSystemWindows =
            false
        window?.findViewById<View>(com.google.android.material.R.id.coordinator)?.fitsSystemWindows =
            false
        contentView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View
            .SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val insets = activity.window.decorView.rootWindowInsets
        (contentView.parent as View).background = null
        contentView.post {
            (contentView.parent as View).background = null
        }
        contentView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            leftMargin = insets.systemWindowInsetLeft
            rightMargin = insets.systemWindowInsetRight
        }
        contentView.requestLayout()
    }

    override fun onStart() {
        super.onStart()
        recyclerView?.let { recyclerView ->
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE ||
                        newState == RecyclerView.SCROLL_STATE_SETTLING
                    ) {
                        sheetBehavior.isDraggable = true
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (recyclerView.canScrollVertically(-1) &&
                        recyclerView.scrollState != RecyclerView.SCROLL_STATE_SETTLING
                    ) {
                        sheetBehavior.isDraggable = false
                    }
                }
            })
        }
    }

    abstract fun createBinding(inflater: LayoutInflater): VB
}