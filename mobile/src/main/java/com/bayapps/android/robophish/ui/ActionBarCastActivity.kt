package com.bayapps.android.robophish.ui

import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentManager
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.inject
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.IntroductoryOverlay
import com.google.android.material.navigation.NavigationView
import timber.log.Timber
import javax.inject.Inject

/**
 * Abstract activity with toolbar, navigation drawer and cast support.
 */
abstract class ActionBarCastActivity : AppCompatActivity() {
    @Inject lateinit var castContext: CastContext

    private var mediaRouteMenuItem: MenuItem? = null
    private lateinit var toolbar: Toolbar
    private var drawerToggle: ActionBarDrawerToggle? = null
    private var drawerLayout: DrawerLayout? = null
    private var toolbarInitialized = false
    private var itemToOpenWhenDrawerCloses = -1
    private val mainHandler = Handler(Looper.getMainLooper())

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (drawerLayout?.isDrawerOpen(GravityCompat.START) == true) {
                drawerLayout?.closeDrawers()
                return
            }
            val fragmentManager = supportFragmentManager
            if (fragmentManager.backStackEntryCount > 0) {
                fragmentManager.popBackStack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    private val backStackChangedListener = FragmentManager.OnBackStackChangedListener {
        updateDrawerToggle()
    }

    private val castStateListener = CastStateListener { newState ->
        if (newState == CastState.NO_DEVICES_AVAILABLE) return@CastStateListener
        mainHandler.postDelayed({
            if (mediaRouteMenuItem?.isVisible == true) {
                Timber.d("Cast Icon is visible")
                showFtu()
            }
        }, DELAY_MILLIS)
    }

    private val drawerListener = object : DrawerLayout.DrawerListener {
        override fun onDrawerClosed(drawerView: View) {
            drawerToggle?.onDrawerClosed(drawerView)
            if (itemToOpenWhenDrawerCloses >= 0) {
                val extras = ActivityOptions.makeCustomAnimation(
                    this@ActionBarCastActivity,
                    R.anim.fade_in,
                    R.anim.fade_out
                ).toBundle()

                val activityClass = when (itemToOpenWhenDrawerCloses) {
                    R.id.navigation_allmusic -> MusicPlayerActivity::class.java
                    R.id.navigation_playlists -> PlaceholderActivity::class.java
                    R.id.navigation_downloads -> PlaceholderActivity::class.java
                    R.id.navigation_about -> AboutActivity::class.java
                    else -> null
                }
                if (activityClass != null) {
                    startActivity(Intent(this@ActionBarCastActivity, activityClass), extras)
                    finish()
                }
            }
        }

        override fun onDrawerStateChanged(newState: Int) {
            drawerToggle?.onDrawerStateChanged(newState)
        }

        override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
            drawerToggle?.onDrawerSlide(drawerView, slideOffset)
        }

        override fun onDrawerOpened(drawerView: View) {
            drawerToggle?.onDrawerOpened(drawerView)
            supportActionBar?.setTitle(R.string.app_name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("Activity onCreate")
        inject()
        CastContext.getSharedInstance(this)
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    override fun onStart() {
        super.onStart()
        if (!toolbarInitialized) {
            throw IllegalStateException(
                "You must run super.initializeToolbar at the end of your onCreate method"
            )
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle?.syncState()
    }

    override fun onResume() {
        super.onResume()
        castContext.addCastStateListener(castStateListener)
        supportFragmentManager.addOnBackStackChangedListener(backStackChangedListener)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        drawerToggle?.onConfigurationChanged(newConfig)
    }

    override fun onPause() {
        super.onPause()
        castContext.removeCastStateListener(castStateListener)
        supportFragmentManager.removeOnBackStackChangedListener(backStackChangedListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.main, menu)
        CastButtonFactory.setUpMediaRouteButton(
            applicationContext,
            menu,
            R.id.media_route_menu_item
        )
        mediaRouteMenuItem = menu.findItem(R.id.media_route_menu_item)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle?.onOptionsItemSelected(item) == true) {
            return true
        }
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun setTitle(title: CharSequence?) {
        super.setTitle(title)
        toolbar.title = title
    }

    fun setSubtitle(title: CharSequence?) {
        toolbar.subtitle = title
    }

    override fun setTitle(titleId: Int) {
        super.setTitle(titleId)
        toolbar.setTitle(titleId)
    }

    protected fun initializeToolbar() {
        toolbar = findViewById(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main)

        drawerLayout = findViewById(R.id.drawer_layout)
        if (drawerLayout != null) {
            val navigationView: NavigationView = findViewById(R.id.nav_view)
            drawerToggle = ActionBarDrawerToggle(
                this,
                drawerLayout,
                toolbar,
                R.string.open_content_drawer,
                R.string.close_content_drawer
            )
            drawerLayout?.addDrawerListener(drawerListener)
            populateDrawerItems(navigationView)
            setSupportActionBar(toolbar)
            updateDrawerToggle()
        } else {
            setSupportActionBar(toolbar)
        }

        toolbarInitialized = true
    }

    private fun populateDrawerItems(navigationView: NavigationView) {
        navigationView.setNavigationItemSelectedListener { menuItem ->
            menuItem.isChecked = true
            itemToOpenWhenDrawerCloses = menuItem.itemId
            drawerLayout?.closeDrawers()
            true
        }
        when {
            MusicPlayerActivity::class.java.isAssignableFrom(javaClass) -> {
                navigationView.setCheckedItem(R.id.navigation_allmusic)
            }
            PlaceholderActivity::class.java.isAssignableFrom(javaClass) -> {
                navigationView.setCheckedItem(R.id.navigation_playlists)
            }
        }
    }

    open fun updateDrawerToggle() {
        val toggle = drawerToggle ?: return
        val isRoot = supportFragmentManager.backStackEntryCount == 0
        toggle.isDrawerIndicatorEnabled = isRoot
        supportActionBar?.apply {
            setDisplayShowHomeEnabled(!isRoot)
            setDisplayHomeAsUpEnabled(!isRoot)
            setHomeButtonEnabled(!isRoot)
        }
        if (isRoot) {
            toggle.syncState()
        }
    }

    private fun showFtu() {
        val item = mediaRouteMenuItem ?: return
        IntroductoryOverlay.Builder(this, item)
            .setTitleText(R.string.touch_to_cast)
            .setSingleTime()
            .build()
            .show()
    }

    companion object {
        private const val DELAY_MILLIS = 1000L
    }
}
