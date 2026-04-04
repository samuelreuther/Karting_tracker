package com.kartingtracker.ui.sessions

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kartingtracker.R
import com.kartingtracker.data.Session
import com.kartingtracker.databinding.FragmentSessionListBinding
import com.kartingtracker.ui.AppViewModelFactory
import com.kartingtracker.ui.SessionListItemUiState
import com.kartingtracker.ui.SessionViewModel
import kotlinx.coroutines.launch

class SessionListFragment : Fragment() {
    private var _binding: FragmentSessionListBinding? = null
    private val binding get() = _binding!!

    private val sessionViewModel: SessionViewModel by activityViewModels {
        AppViewModelFactory(requireActivity().application)
    }

    private val adapter = SessionListAdapter(::showOpenOptions, ::showDeleteOptions)
    private var suppressFilterCallbacks = false

    private val exportBackupDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val success = sessionViewModel.exportBackup(uri)
            val message = if (success) getString(R.string.backup_export_success) else getString(R.string.backup_export_failed)
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private val importBackupDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val success = sessionViewModel.importBackup(uri)
            val message = if (success) getString(R.string.backup_import_success) else getString(R.string.backup_import_failed)
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSessionListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.sessionRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.sessionRecyclerView.adapter = adapter

        binding.trackFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!suppressFilterCallbacks) {
                    val label = parent?.getItemAtPosition(position) as? String ?: return
                    sessionViewModel.selectSessionFilter(label)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.exportBackupButton.setOnClickListener {
            val backupName = "karting_tracker_backup_${System.currentTimeMillis()}.zip"
            exportBackupDocumentLauncher.launch(backupName)
        }
        binding.importBackupButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.import_backup)
                .setMessage(R.string.backup_import_warning)
                .setPositiveButton(R.string.import_backup) { _, _ ->
                    importBackupDocumentLauncher.launch(arrayOf("application/zip"))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionViewModel.sessionListUiState.collect { state ->
                    updateFilterSpinner(state.filterOptions, state.selectedFilter)
                    adapter.submitList(state.sessions)
                    binding.emptyStateLabel.visibility = if (state.sessions.isEmpty()) View.VISIBLE else View.GONE
                    binding.sessionRecyclerView.visibility = if (state.sessions.isEmpty()) View.GONE else View.VISIBLE
                    binding.emptyStateLabel.text = if (state.selectedFilter == SessionViewModel.ALL_TRACKS_FILTER) {
                        getString(R.string.no_saved_sessions)
                    } else {
                        getString(R.string.no_saved_sessions_for_track, state.selectedFilter)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun updateFilterSpinner(options: List<String>, selectedFilter: String) {
        suppressFilterCallbacks = true
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.trackFilterSpinner.adapter = adapter
        val selectedIndex = options.indexOf(selectedFilter).coerceAtLeast(0)
        binding.trackFilterSpinner.setSelection(selectedIndex, false)
        suppressFilterCallbacks = false
    }

    private fun showOpenOptions(item: SessionListItemUiState) {
        val session = item.session
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(session.trackName)
            .setItems(
                arrayOf(
                    getString(R.string.open_laps),
                    getString(R.string.open_comparison),
                    getString(R.string.reprocess_session),
                    getString(R.string.export_csv)
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        sessionViewModel.loadSession(session)
                        findNavController().navigate(R.id.action_sessionListFragment_to_lapsFragment)
                    }

                    1 -> {
                        sessionViewModel.loadSession(session)
                        findNavController().navigate(R.id.action_sessionListFragment_to_comparisonFragment)
                    }

                    2 -> {
                        sessionViewModel.reprocessSession(session)
                    }

                    3 -> {
                        val file = sessionViewModel.exportSessionCsv(session)
                        Toast.makeText(requireContext(), getString(R.string.csv_export_success, file.absolutePath), Toast.LENGTH_LONG).show()
                        shareCsv(file)
                    }
                }
            }
            .show()
    }

    private fun showDeleteOptions(item: SessionListItemUiState) {
        val session = item.session
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(session.trackName)
            .setItems(
                arrayOf(
                    getString(R.string.delete_session),
                    getString(R.string.delete_track)
                )
            ) { _, which ->
                when (which) {
                    0 -> confirmDeleteSession(session)
                    1 -> confirmDeleteTrack(session.trackName)
                }
            }
            .show()
    }

    private fun confirmDeleteSession(session: Session) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_session)
            .setMessage(getString(R.string.delete_session_confirmation, session.trackName))
            .setPositiveButton(R.string.delete_session) { _, _ ->
                sessionViewModel.deleteSession(session)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteTrack(trackName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_track)
            .setMessage(getString(R.string.delete_track_confirmation, trackName))
            .setPositiveButton(R.string.delete_track) { _, _ ->
                sessionViewModel.deleteTrack(trackName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareCsv(file: java.io.File) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_csv)))
    }
}
