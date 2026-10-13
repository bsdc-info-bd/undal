package com.undal.syl.app.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.util.UndalResult
import com.undal.syl.app.core.util.Validators
import com.undal.syl.app.data.repo.AuthRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Every field error and every busy flag for a form, in one immutable object. */
data class AuthFormState(
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val nameError: String? = null,
    val emailError: String? = null,
    val phoneError: String? = null,
    val passwordError: String? = null,
    val confirmError: String? = null,
    val busy: Boolean = false,
    val banner: String? = null,
    val bannerIsError: Boolean = true,
    val done: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepo: AuthRepo
) : ViewModel() {

    private val _state = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = _state.asStateFlow()

    fun onEmailChange(value: String) {
        _state.value = _state.value.copy(email = value, emailError = null, banner = null)
    }

    fun onPasswordChange(value: String) {
        _state.value = _state.value.copy(password = value, passwordError = null, banner = null)
    }

    fun submit(onSignedIn: () -> Unit) {
        val current = _state.value
        val emailError = Validators.email(current.email)
        val passwordError = if (current.password.isEmpty()) "Please enter your password." else null
        if (emailError != null || passwordError != null) {
            _state.value = current.copy(emailError = emailError, passwordError = passwordError)
            return
        }

        _state.value = current.copy(busy = true, banner = null)
        viewModelScope.launch {
            when (val result = authRepo.login(current.email, current.password)) {
                is UndalResult.Success -> {
                    _state.value = _state.value.copy(busy = false)
                    onSignedIn()
                }

                is UndalResult.Failure -> {
                    _state.value = _state.value.copy(busy = false, banner = result.message, bannerIsError = true)
                }
            }
        }
    }

    fun resetPassword() {
        val current = _state.value
        val emailError = Validators.email(current.email)
        if (emailError != null) {
            _state.value = current.copy(emailError = emailError, banner = null)
            return
        }
        _state.value = current.copy(busy = true, banner = null)
        viewModelScope.launch {
            when (val result = authRepo.sendPasswordReset(current.email)) {
                is UndalResult.Success -> _state.value = _state.value.copy(
                    busy = false,
                    banner = "Password reset email sent to ${current.email.trim()}. Check the inbox and spam folder.",
                    bannerIsError = false
                )

                is UndalResult.Failure -> _state.value = _state.value.copy(
                    busy = false,
                    banner = result.message,
                    bannerIsError = true
                )
            }
        }
    }
}

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val authRepo: AuthRepo
) : ViewModel() {

    private val _state = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = _state.asStateFlow()

    fun onNameChange(value: String) {
        _state.value = _state.value.copy(name = value, nameError = null, banner = null)
    }

    fun onEmailChange(value: String) {
        _state.value = _state.value.copy(email = value, emailError = null, banner = null)
    }

    fun onPhoneChange(value: String) {
        val digits = value.filter { it.isDigit() }.take(11)
        _state.value = _state.value.copy(phone = digits, phoneError = null, banner = null)
    }

    fun onPasswordChange(value: String) {
        _state.value = _state.value.copy(password = value, passwordError = null, confirmError = null, banner = null)
    }

    fun onConfirmChange(value: String) {
        _state.value = _state.value.copy(confirmPassword = value, confirmError = null, banner = null)
    }

    fun submit(onRegistered: () -> Unit) {
        val current = _state.value
        val nameError = Validators.name(current.name)
        val emailError = Validators.email(current.email)
        val phoneError = Validators.bdMobile(current.phone)
        val passwordError = Validators.password(current.password)
        val confirmError = Validators.passwordConfirmation(current.password, current.confirmPassword)

        if (listOf(nameError, emailError, phoneError, passwordError, confirmError).any { it != null }) {
            _state.value = current.copy(
                nameError = nameError,
                emailError = emailError,
                phoneError = phoneError,
                passwordError = passwordError,
                confirmError = confirmError
            )
            return
        }

        _state.value = current.copy(busy = true, banner = null)
        viewModelScope.launch {
            when (val result = authRepo.register(
                name = current.name,
                email = current.email,
                phone = current.phone,
                password = current.password
            )) {
                is UndalResult.Success -> {
                    _state.value = _state.value.copy(busy = false, done = true)
                    onRegistered()
                }

                is UndalResult.Failure -> {
                    _state.value = _state.value.copy(busy = false, banner = result.message, bannerIsError = true)
                }
            }
        }
    }
}
