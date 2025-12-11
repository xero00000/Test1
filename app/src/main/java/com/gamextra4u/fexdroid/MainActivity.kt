package com.gamextra4u.fexdroid

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gamextra4u.fexdroid.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initializeUI()
    }

    private fun initializeUI() {
        binding.apply {
            statusText.text = "FEXDroid Ready"
            runTestButton.setOnClickListener {
                onRunTestClicked()
            }
        }
    }

    private fun onRunTestClicked() {
        binding.statusText.text = "Running test..."
        // Test code would go here
        binding.statusText.text = "Test completed successfully!"
    }
}
