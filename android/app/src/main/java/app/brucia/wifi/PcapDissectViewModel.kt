package app.brucia.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/** Activity-scoped owner of the [PcapDissectSession]: survives configuration changes. */
class PcapDissectViewModel : ViewModel() {
    val session = PcapDissectSession(viewModelScope)
}
