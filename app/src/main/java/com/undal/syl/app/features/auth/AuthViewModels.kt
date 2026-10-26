package com.undal.syl.app.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.undal.syl.app.core.util.UndalDigits
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
    val googleBusy: Boolean = false,
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

    /**
     * Sign-in is the one place a crash is guaranteed to be reported as "the app
     * does not work": the person has typed their details, tapped the button, and
     * the very next thing the phone does is decide whether to keep the process
     * alive. An exception escaping this scope is uncaught, and uncaught means the
     * app closes and the launcher comes back — on a *new* device, where the token
     * read and the first profile read are the first network calls it has ever
     * made and the connection is often the weakest it will ever be.
     *
     * So the failure becomes the form's own banner, with the busy flag cleared:
     * one sentence and a button that still works.
     */
    private val guard = com.undal.syl.app.core.util.UndalGuards.viewModel("signing you in") { message ->
        _state.value = _state.value.copy(busy = false, banner = message, bannerIsError = true)
    }

    fun onEmailChange(value: String) {
        _state.value = _state.value.copy(email = value, emailError = null, banner = null)
    }

    fun onPasswordChange(value: String) {
        _state.value = _state.value.copy(password = value, passwordError = null, banner = null)
    }

    fun signInWithGoogle(context: android.content.Context, onSignedIn: () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, googleBusy = true, banner = null)
        viewModelScope.launch(guard) {
            try {
                val token = GoogleSignIn.idToken(context)
                when (val result = authRepo.signInWithGoogle(token)) {
                    is UndalResult.Success -> {
                        _state.value = _state.value.copy(busy = false)
                        onSignedIn()
                    }
                    is UndalResult.Failure -> _state.value = _state.value.copy(
                        busy = false, banner = result.message, bannerIsError = true
                    )
                }
            } catch (error: Exception) {
                val message = GoogleSignIn.errorMessage(error)
                _state.value = _state.value.copy(busy = false, banner = message, bannerIsError = true)
            } finally {
                _state.value = _state.value.copy(busy = false, googleBusy = false)
            }
        }
    }

    fun submit(onSignedIn: () -> Unit) {
        if (_state.value.busy) return
        val current = _state.value
        val emailError = Validators.email(current.email)
        val passwordError = if (current.password.isEmpty()) "Please enter your password." else null
        if (emailError != null || passwordError != null) {
            _state.value = current.copy(emailError = emailError, passwordError = passwordError)
            return
        }

        _state.value = current.copy(busy = true, banner = null)
        viewModelScope.launch(guard) {
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
        if (_state.value.busy) return
        val current = _state.value
        val emailError = Validators.email(current.email)
        if (emailError != null) {
            _state.value = current.copy(emailError = emailError, banner = null)
            return
        }
        _state.value = current.copy(busy = true, banner = null)
        viewModelScope.launch(guard) {
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

    /** As in [LoginViewModel]: a failed sign-up is a banner, never a dead process. */
    private val guard = com.undal.syl.app.core.util.UndalGuards.viewModel("creating your account") { message ->
        _state.value = _state.value.copy(busy = false, banner = message, bannerIsError = true)
    }

    fun onNameChange(value: String) {
        _state.value = _state.value.copy(name = value, nameError = null, banner = null)
    }

    fun onEmailChange(value: String) {
        _state.value = _state.value.copy(email = value, emailError = null, banner = null)
    }

    /**
     * Eleven digits, in ASCII.
     *
     * `isDigit()` is true for ০১২৩৪৫৬৭৮৯, so a customer typing their own number
     * with a Bangla keyboard used to end up with a Bengali-digit string in state;
     * validation could be taught to read it, but the security rules store
     * `^01[3-9][0-9]{8}$` and would refuse the write with "Missing or insufficient
     * permissions". Convert once, here, and the number is the same number
     * everywhere afterwards.
     */
    fun onPhoneChange(value: String) {
        val digits = UndalDigits.toAscii(value).filter { it in '0'..'9' }.take(11)
        _state.value = _state.value.copy(phone = digits, phoneError = null, banner = null)
    }

    fun onPasswordChange(value: String) {
        _state.value = _state.value.copy(password = value, passwordError = null, confirmError = null, banner = null)
    }

    fun onConfirmChange(value: String) {
        _state.value = _state.value.copy(confirmPassword = value, confirmError = null, banner = null)
    }

    fun signInWithGoogle(context: android.content.Context, onSignedIn: () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, googleBusy = true, banner = null)
        viewModelScope.launch(guard) {
            try {
                val token = GoogleSignIn.idToken(context)
                when (val result = authRepo.signInWithGoogle(token)) {
                    is UndalResult.Success -> {
                        _state.value = _state.value.copy(busy = false)
                        onSignedIn()
                    }
                    is UndalResult.Failure -> _state.value = _state.value.copy(
                        busy = false, banner = result.message, bannerIsError = true
                    )
                }
            } catch (error: Exception) {
                val message = GoogleSignIn.errorMessage(error)
                _state.value = _state.value.copy(busy = false, banner = message, bannerIsError = true)
            } finally {
                _state.value = _state.value.copy(busy = false, googleBusy = false)
            }
        }
    }

    fun submit(onRegistered: () -> Unit) {
        if (_state.value.busy) return
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
        viewModelScope.launch(guard) {
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
