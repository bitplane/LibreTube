package com.github.libretube.fragments

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView.GONE
import android.widget.TextView.OnEditorActionListener
import android.widget.TextView.VISIBLE
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.adapters.SearchAdapter
import com.github.libretube.adapters.SearchHistoryAdapter
import com.github.libretube.hideKeyboard
import com.github.libretube.util.RetrofitInstance
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

class SearchFragment : Fragment() {
    private val TAG = "SearchFragment"
    private var selectedFilter = 0
    private var apiSearchFilter = "all"
    private var nextPage: String? = null
    private lateinit var searchRecView: RecyclerView
    private var searchAdapter: SearchAdapter? = null
    private var isLoading: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchRecView = view.findViewById<RecyclerView>(R.id.search_recycler)

        val autoTextView = view.findViewById<AutoCompleteTextView>(R.id.autoCompleteTextView)
        val clearSearchButton = view.findViewById<ImageView>(R.id.clearSearch_imageView)
        val historyRecycler = view.findViewById<RecyclerView>(R.id.history_recycler)
        val filterImageView = view.findViewById<ImageView>(R.id.filterMenu_imageView)

        var tempSelectedItem = 0

        val sharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(requireContext())

        clearSearchButton.setOnClickListener {
            autoTextView.text.clear()
        }

        filterImageView.setOnClickListener {
            val filterOptions = arrayOf(
                getString(R.string.all),
                getString(R.string.videos),
                getString(R.string.channels),
                getString(R.string.playlists),
                getString(R.string.music_songs),
                getString(R.string.music_videos),
                getString(R.string.music_albums),
                getString(R.string.music_playlists)
            )

            MaterialAlertDialogBuilder(view.context)
                .setTitle(getString(R.string.choose_filter))
                .setSingleChoiceItems(
                    filterOptions, selectedFilter,
                    DialogInterface.OnClickListener { _, id ->
                        tempSelectedItem = id
                    }
                )
                .setPositiveButton(
                    getString(R.string.okay),
                    DialogInterface.OnClickListener { _, _ ->
                        selectedFilter = tempSelectedItem
                        apiSearchFilter = when (selectedFilter) {
                            0 -> "all"
                            1 -> "videos"
                            2 -> "channels"
                            3 -> "playlists"
                            4 -> "music_songs"
                            5 -> "music_videos"
                            6 -> "music_albums"
                            7 -> "music_playlists"
                            else -> "all"
                        }
                        fetchSearch(autoTextView.text.toString())
                    }
                )
                .setNegativeButton(getString(R.string.cancel), null)
                .create()
                .show()
        }

        // show search history

        searchRecView.visibility = GONE
        historyRecycler.visibility = VISIBLE

        historyRecycler.layoutManager = LinearLayoutManager(view.context)

        val historyList = getHistory()
        if (historyList.isNotEmpty()) {
            historyRecycler.adapter =
                SearchHistoryAdapter(requireContext(), historyList, autoTextView)
        }

        searchRecView.layoutManager = GridLayoutManager(view.context, 1)
        autoTextView.requestFocus()
        val imm =
            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(autoTextView, InputMethodManager.SHOW_IMPLICIT)
        autoTextView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s!! != "") {
                    searchRecView.visibility = VISIBLE
                    historyRecycler.visibility = GONE
                    searchRecView.adapter = null

                    searchRecView.viewTreeObserver
                        .addOnScrollChangedListener {
                            if (!searchRecView.canScrollVertically(1)) {
                                fetchNextSearchItems(autoTextView.text.toString())
                            }
                        }

                    GlobalScope.launch {
                        fetchSuggestions(s.toString(), autoTextView)
                        delay(1000)
                        fetchSearch(s.toString())
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (s!!.isEmpty()) {
                    searchRecView.visibility = GONE
                    historyRecycler.visibility = VISIBLE
                    val historyList = getHistory()
                    if (historyList.isNotEmpty()) {
                        historyRecycler.adapter =
                            SearchHistoryAdapter(requireContext(), historyList, autoTextView)
                    }
                }
            }
        })
        autoTextView.setOnEditorActionListener(
            OnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard()
                    autoTextView.dismissDropDown()
                    if (sharedPreferences.getBoolean(
                            "search_history_toggle",
                            true
                        )
                    ) {
                        val newString = autoTextView.text.toString()
                        addToHistory(newString)
                    }
                    return@OnEditorActionListener true
                }
                false
            }
        )
        autoTextView.setOnItemClickListener { _, _, _, _ ->
            hideKeyboard()
        }
    }

    private fun fetchSuggestions(query: String, autoTextView: AutoCompleteTextView) {
        lifecycleScope.launchWhenCreated {
            val response = try {
                RetrofitInstance.api.getSuggestions(query)
            } catch (e: IOException) {
                println(e)
                Log.e(TAG, "IOException, you might not have internet connection")
                return@launchWhenCreated
            } catch (e: HttpException) {
                Log.e(TAG, "HttpException, unexpected response")
                return@launchWhenCreated
            }
            val adapter =
                ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, response)
            autoTextView.setAdapter(adapter)
        }
    }

    private fun fetchSearch(query: String) {
        lifecycleScope.launchWhenCreated {
            val response = try {
                RetrofitInstance.api.getSearchResults(query, apiSearchFilter)
            } catch (e: IOException) {
                println(e)
                Log.e(TAG, "IOException, you might not have internet connection $e")
                return@launchWhenCreated
            } catch (e: HttpException) {
                Log.e(TAG, "HttpException, unexpected response")
                return@launchWhenCreated
            }
            nextPage = response.nextpage
            if (response.items!!.isNotEmpty()) {
                runOnUiThread {
                    searchAdapter = SearchAdapter(response.items, childFragmentManager)
                    searchRecView.adapter = searchAdapter
                }
            }
            isLoading = false
        }
    }

    private fun fetchNextSearchItems(query: String) {
        lifecycleScope.launchWhenCreated {
            if (!isLoading) {
                isLoading = true
                val response = try {
                    RetrofitInstance.api.getSearchResultsNextPage(
                        query,
                        apiSearchFilter,
                        nextPage!!
                    )
                } catch (e: IOException) {
                    println(e)
                    Log.e(TAG, "IOException, you might not have internet connection")
                    return@launchWhenCreated
                } catch (e: HttpException) {
                    Log.e(TAG, "HttpException, unexpected response," + e.response())
                    return@launchWhenCreated
                }
                nextPage = response.nextpage
                searchAdapter?.updateItems(response.items!!)
                isLoading = false
            }
        }
    }

    private fun Fragment?.runOnUiThread(action: () -> Unit) {
        this ?: return
        if (!isAdded) return // Fragment not attached to an Activity
        activity?.runOnUiThread(action)
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.setSoftInputMode(SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    override fun onStop() {
        super.onStop()
        hideKeyboard()
    }

    private fun addToHistory(query: String) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        var historyList = getHistory()

        if (historyList.isNotEmpty() && query == historyList[historyList.size - 1]) {
            return
        } else if (query == "") {
            return
        } else {
            historyList = historyList + query
        }

        if (historyList.size > 10) {
            historyList = historyList.takeLast(10)
        }

        val set: Set<String> = HashSet(historyList)

        sharedPreferences.edit().putStringSet("search_history", set)
            .apply()
    }

    private fun getHistory(): List<String> {
        return try {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val set: Set<String> = sharedPreferences.getStringSet("search_history", HashSet())!!
            set.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
