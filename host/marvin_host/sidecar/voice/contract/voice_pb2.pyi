# SPDX-License-Identifier: MIT
# Generated from marvin/voice/v1/voice.proto by host/scripts/gen_voice_contract.py: do not edit.
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class AudioRoute(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    AUDIO_ROUTE_UNSPECIFIED: _ClassVar[AudioRoute]
    AUDIO_ROUTE_LOCAL: _ClassVar[AudioRoute]
    AUDIO_ROUTE_ROBOT: _ClassVar[AudioRoute]
AUDIO_ROUTE_UNSPECIFIED: AudioRoute
AUDIO_ROUTE_LOCAL: AudioRoute
AUDIO_ROUTE_ROBOT: AudioRoute

class CoreToVoice(_message.Message):
    __slots__ = ("configure", "robot_mic", "reply_start", "text", "reply_end", "say", "listen_now", "mute", "stop", "filler", "robot_link", "ask", "earcon", "enroll")
    CONFIGURE_FIELD_NUMBER: _ClassVar[int]
    ROBOT_MIC_FIELD_NUMBER: _ClassVar[int]
    REPLY_START_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    REPLY_END_FIELD_NUMBER: _ClassVar[int]
    SAY_FIELD_NUMBER: _ClassVar[int]
    LISTEN_NOW_FIELD_NUMBER: _ClassVar[int]
    MUTE_FIELD_NUMBER: _ClassVar[int]
    STOP_FIELD_NUMBER: _ClassVar[int]
    FILLER_FIELD_NUMBER: _ClassVar[int]
    ROBOT_LINK_FIELD_NUMBER: _ClassVar[int]
    ASK_FIELD_NUMBER: _ClassVar[int]
    EARCON_FIELD_NUMBER: _ClassVar[int]
    ENROLL_FIELD_NUMBER: _ClassVar[int]
    configure: Configure
    robot_mic: AudioFrame
    reply_start: ReplyStart
    text: TextPiece
    reply_end: ReplyEnd
    say: Say
    listen_now: ListenNow
    mute: Mute
    stop: StopSpeaking
    filler: Filler
    robot_link: RobotLink
    ask: Ask
    earcon: Earcon
    enroll: Enroll
    def __init__(self, configure: _Optional[_Union[Configure, _Mapping]] = ..., robot_mic: _Optional[_Union[AudioFrame, _Mapping]] = ..., reply_start: _Optional[_Union[ReplyStart, _Mapping]] = ..., text: _Optional[_Union[TextPiece, _Mapping]] = ..., reply_end: _Optional[_Union[ReplyEnd, _Mapping]] = ..., say: _Optional[_Union[Say, _Mapping]] = ..., listen_now: _Optional[_Union[ListenNow, _Mapping]] = ..., mute: _Optional[_Union[Mute, _Mapping]] = ..., stop: _Optional[_Union[StopSpeaking, _Mapping]] = ..., filler: _Optional[_Union[Filler, _Mapping]] = ..., robot_link: _Optional[_Union[RobotLink, _Mapping]] = ..., ask: _Optional[_Union[Ask, _Mapping]] = ..., earcon: _Optional[_Union[Earcon, _Mapping]] = ..., enroll: _Optional[_Union[Enroll, _Mapping]] = ...) -> None: ...

class Configure(_message.Message):
    __slots__ = ("contract_version", "settings", "route", "device")
    CONTRACT_VERSION_FIELD_NUMBER: _ClassVar[int]
    SETTINGS_FIELD_NUMBER: _ClassVar[int]
    ROUTE_FIELD_NUMBER: _ClassVar[int]
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    contract_version: int
    settings: VoiceSettings
    route: AudioRoute
    device: AudioDeviceProfile
    def __init__(self, contract_version: _Optional[int] = ..., settings: _Optional[_Union[VoiceSettings, _Mapping]] = ..., route: _Optional[_Union[AudioRoute, str]] = ..., device: _Optional[_Union[AudioDeviceProfile, _Mapping]] = ...) -> None: ...

class AudioDeviceProfile(_message.Message):
    __slots__ = ("kind", "robot", "duplex", "echo_tail_s", "output_latency_s", "vad_min_dbfs", "speech_min_dbfs", "vad_aggressiveness", "has_earcons", "has_aec", "input_device", "output_device", "vad_threshold")
    KIND_FIELD_NUMBER: _ClassVar[int]
    ROBOT_FIELD_NUMBER: _ClassVar[int]
    DUPLEX_FIELD_NUMBER: _ClassVar[int]
    ECHO_TAIL_S_FIELD_NUMBER: _ClassVar[int]
    OUTPUT_LATENCY_S_FIELD_NUMBER: _ClassVar[int]
    VAD_MIN_DBFS_FIELD_NUMBER: _ClassVar[int]
    SPEECH_MIN_DBFS_FIELD_NUMBER: _ClassVar[int]
    VAD_AGGRESSIVENESS_FIELD_NUMBER: _ClassVar[int]
    HAS_EARCONS_FIELD_NUMBER: _ClassVar[int]
    HAS_AEC_FIELD_NUMBER: _ClassVar[int]
    INPUT_DEVICE_FIELD_NUMBER: _ClassVar[int]
    OUTPUT_DEVICE_FIELD_NUMBER: _ClassVar[int]
    VAD_THRESHOLD_FIELD_NUMBER: _ClassVar[int]
    kind: str
    robot: str
    duplex: bool
    echo_tail_s: float
    output_latency_s: float
    vad_min_dbfs: float
    speech_min_dbfs: float
    vad_aggressiveness: int
    has_earcons: bool
    has_aec: bool
    input_device: str
    output_device: str
    vad_threshold: float
    def __init__(self, kind: _Optional[str] = ..., robot: _Optional[str] = ..., duplex: _Optional[bool] = ..., echo_tail_s: _Optional[float] = ..., output_latency_s: _Optional[float] = ..., vad_min_dbfs: _Optional[float] = ..., speech_min_dbfs: _Optional[float] = ..., vad_aggressiveness: _Optional[int] = ..., has_earcons: _Optional[bool] = ..., has_aec: _Optional[bool] = ..., input_device: _Optional[str] = ..., output_device: _Optional[str] = ..., vad_threshold: _Optional[float] = ...) -> None: ...

class VoiceSettings(_message.Message):
    __slots__ = ("stt", "stt_model", "tts", "tts_voice", "language", "default_language", "wake", "duplex", "echo_tail_s", "follow_up_s", "listen_window_s", "speculative_stt", "end_silence_ms", "chime", "input_device", "output_device", "continue_grace_s", "end_silence_long_ms", "continue_join_s", "speculate_after_s", "vad", "turn", "turn_check_ms", "turn_max_ms", "vocabulary", "voiceprints", "known_voices_only", "speaker_threshold")
    STT_FIELD_NUMBER: _ClassVar[int]
    STT_MODEL_FIELD_NUMBER: _ClassVar[int]
    TTS_FIELD_NUMBER: _ClassVar[int]
    TTS_VOICE_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    DEFAULT_LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    WAKE_FIELD_NUMBER: _ClassVar[int]
    DUPLEX_FIELD_NUMBER: _ClassVar[int]
    ECHO_TAIL_S_FIELD_NUMBER: _ClassVar[int]
    FOLLOW_UP_S_FIELD_NUMBER: _ClassVar[int]
    LISTEN_WINDOW_S_FIELD_NUMBER: _ClassVar[int]
    SPECULATIVE_STT_FIELD_NUMBER: _ClassVar[int]
    END_SILENCE_MS_FIELD_NUMBER: _ClassVar[int]
    CHIME_FIELD_NUMBER: _ClassVar[int]
    INPUT_DEVICE_FIELD_NUMBER: _ClassVar[int]
    OUTPUT_DEVICE_FIELD_NUMBER: _ClassVar[int]
    CONTINUE_GRACE_S_FIELD_NUMBER: _ClassVar[int]
    END_SILENCE_LONG_MS_FIELD_NUMBER: _ClassVar[int]
    CONTINUE_JOIN_S_FIELD_NUMBER: _ClassVar[int]
    SPECULATE_AFTER_S_FIELD_NUMBER: _ClassVar[int]
    VAD_FIELD_NUMBER: _ClassVar[int]
    TURN_FIELD_NUMBER: _ClassVar[int]
    TURN_CHECK_MS_FIELD_NUMBER: _ClassVar[int]
    TURN_MAX_MS_FIELD_NUMBER: _ClassVar[int]
    VOCABULARY_FIELD_NUMBER: _ClassVar[int]
    VOICEPRINTS_FIELD_NUMBER: _ClassVar[int]
    KNOWN_VOICES_ONLY_FIELD_NUMBER: _ClassVar[int]
    SPEAKER_THRESHOLD_FIELD_NUMBER: _ClassVar[int]
    stt: str
    stt_model: str
    tts: str
    tts_voice: str
    language: str
    default_language: str
    wake: bool
    duplex: bool
    echo_tail_s: float
    follow_up_s: float
    listen_window_s: float
    speculative_stt: bool
    end_silence_ms: float
    chime: bool
    input_device: str
    output_device: str
    continue_grace_s: float
    end_silence_long_ms: float
    continue_join_s: float
    speculate_after_s: float
    vad: str
    turn: str
    turn_check_ms: float
    turn_max_ms: float
    vocabulary: _containers.RepeatedScalarFieldContainer[str]
    voiceprints: _containers.RepeatedCompositeFieldContainer[Voiceprint]
    known_voices_only: bool
    speaker_threshold: float
    def __init__(self, stt: _Optional[str] = ..., stt_model: _Optional[str] = ..., tts: _Optional[str] = ..., tts_voice: _Optional[str] = ..., language: _Optional[str] = ..., default_language: _Optional[str] = ..., wake: _Optional[bool] = ..., duplex: _Optional[bool] = ..., echo_tail_s: _Optional[float] = ..., follow_up_s: _Optional[float] = ..., listen_window_s: _Optional[float] = ..., speculative_stt: _Optional[bool] = ..., end_silence_ms: _Optional[float] = ..., chime: _Optional[bool] = ..., input_device: _Optional[str] = ..., output_device: _Optional[str] = ..., continue_grace_s: _Optional[float] = ..., end_silence_long_ms: _Optional[float] = ..., continue_join_s: _Optional[float] = ..., speculate_after_s: _Optional[float] = ..., vad: _Optional[str] = ..., turn: _Optional[str] = ..., turn_check_ms: _Optional[float] = ..., turn_max_ms: _Optional[float] = ..., vocabulary: _Optional[_Iterable[str]] = ..., voiceprints: _Optional[_Iterable[_Union[Voiceprint, _Mapping]]] = ..., known_voices_only: _Optional[bool] = ..., speaker_threshold: _Optional[float] = ...) -> None: ...

class Voiceprint(_message.Message):
    __slots__ = ("name", "embedding", "model")
    NAME_FIELD_NUMBER: _ClassVar[int]
    EMBEDDING_FIELD_NUMBER: _ClassVar[int]
    MODEL_FIELD_NUMBER: _ClassVar[int]
    name: str
    embedding: _containers.RepeatedScalarFieldContainer[float]
    model: str
    def __init__(self, name: _Optional[str] = ..., embedding: _Optional[_Iterable[float]] = ..., model: _Optional[str] = ...) -> None: ...

class AudioFrame(_message.Message):
    __slots__ = ("device", "sample_index", "robot_time_us", "pcm")
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    SAMPLE_INDEX_FIELD_NUMBER: _ClassVar[int]
    ROBOT_TIME_US_FIELD_NUMBER: _ClassVar[int]
    PCM_FIELD_NUMBER: _ClassVar[int]
    device: str
    sample_index: int
    robot_time_us: int
    pcm: bytes
    def __init__(self, device: _Optional[str] = ..., sample_index: _Optional[int] = ..., robot_time_us: _Optional[int] = ..., pcm: _Optional[bytes] = ...) -> None: ...

class ReplyStart(_message.Message):
    __slots__ = ("reply_id", "language", "proactive", "utterance_uid")
    REPLY_ID_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    PROACTIVE_FIELD_NUMBER: _ClassVar[int]
    UTTERANCE_UID_FIELD_NUMBER: _ClassVar[int]
    reply_id: int
    language: str
    proactive: bool
    utterance_uid: int
    def __init__(self, reply_id: _Optional[int] = ..., language: _Optional[str] = ..., proactive: _Optional[bool] = ..., utterance_uid: _Optional[int] = ...) -> None: ...

class TextPiece(_message.Message):
    __slots__ = ("reply_id", "text", "language", "clause")
    REPLY_ID_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    CLAUSE_FIELD_NUMBER: _ClassVar[int]
    reply_id: int
    text: str
    language: str
    clause: bool
    def __init__(self, reply_id: _Optional[int] = ..., text: _Optional[str] = ..., language: _Optional[str] = ..., clause: _Optional[bool] = ...) -> None: ...

class Clause(_message.Message):
    __slots__ = ("text", "language")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    text: str
    language: str
    def __init__(self, text: _Optional[str] = ..., language: _Optional[str] = ...) -> None: ...

class ReplyEnd(_message.Message):
    __slots__ = ("reply_id", "error")
    REPLY_ID_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    reply_id: int
    error: str
    def __init__(self, reply_id: _Optional[int] = ..., error: _Optional[str] = ...) -> None: ...

class Say(_message.Message):
    __slots__ = ("reply_id", "text", "language", "force", "proactive", "clauses")
    REPLY_ID_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    FORCE_FIELD_NUMBER: _ClassVar[int]
    PROACTIVE_FIELD_NUMBER: _ClassVar[int]
    CLAUSES_FIELD_NUMBER: _ClassVar[int]
    reply_id: int
    text: str
    language: str
    force: bool
    proactive: bool
    clauses: _containers.RepeatedCompositeFieldContainer[Clause]
    def __init__(self, reply_id: _Optional[int] = ..., text: _Optional[str] = ..., language: _Optional[str] = ..., force: _Optional[bool] = ..., proactive: _Optional[bool] = ..., clauses: _Optional[_Iterable[_Union[Clause, _Mapping]]] = ...) -> None: ...

class ListenNow(_message.Message):
    __slots__ = ("on",)
    ON_FIELD_NUMBER: _ClassVar[int]
    on: bool
    def __init__(self, on: _Optional[bool] = ...) -> None: ...

class Mute(_message.Message):
    __slots__ = ("muted",)
    MUTED_FIELD_NUMBER: _ClassVar[int]
    muted: bool
    def __init__(self, muted: _Optional[bool] = ...) -> None: ...

class StopSpeaking(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class Filler(_message.Message):
    __slots__ = ("reply_id", "text", "language")
    REPLY_ID_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    reply_id: int
    text: str
    language: str
    def __init__(self, reply_id: _Optional[int] = ..., text: _Optional[str] = ..., language: _Optional[str] = ...) -> None: ...

class Earcon(_message.Message):
    __slots__ = ("sound",)
    SOUND_FIELD_NUMBER: _ClassVar[int]
    sound: str
    def __init__(self, sound: _Optional[str] = ...) -> None: ...

class Enroll(_message.Message):
    __slots__ = ("action", "name", "sentences", "noise_s")
    ACTION_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    SENTENCES_FIELD_NUMBER: _ClassVar[int]
    NOISE_S_FIELD_NUMBER: _ClassVar[int]
    action: str
    name: str
    sentences: _containers.RepeatedScalarFieldContainer[str]
    noise_s: float
    def __init__(self, action: _Optional[str] = ..., name: _Optional[str] = ..., sentences: _Optional[_Iterable[str]] = ..., noise_s: _Optional[float] = ...) -> None: ...

class Ask(_message.Message):
    __slots__ = ("text", "language")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    text: str
    language: str
    def __init__(self, text: _Optional[str] = ..., language: _Optional[str] = ...) -> None: ...

class RobotLink(_message.Message):
    __slots__ = ("device", "connected", "has_audio")
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    CONNECTED_FIELD_NUMBER: _ClassVar[int]
    HAS_AUDIO_FIELD_NUMBER: _ClassVar[int]
    device: str
    connected: bool
    has_audio: bool
    def __init__(self, device: _Optional[str] = ..., connected: _Optional[bool] = ..., has_audio: _Optional[bool] = ...) -> None: ...

class VoiceToCore(_message.Message):
    __slots__ = ("status", "heard", "ignored", "level", "partial", "robot_speaker", "ctrl", "say", "interrupted", "sound", "utterance", "spoken", "enroll", "hearing")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    HEARD_FIELD_NUMBER: _ClassVar[int]
    IGNORED_FIELD_NUMBER: _ClassVar[int]
    LEVEL_FIELD_NUMBER: _ClassVar[int]
    PARTIAL_FIELD_NUMBER: _ClassVar[int]
    ROBOT_SPEAKER_FIELD_NUMBER: _ClassVar[int]
    CTRL_FIELD_NUMBER: _ClassVar[int]
    SAY_FIELD_NUMBER: _ClassVar[int]
    INTERRUPTED_FIELD_NUMBER: _ClassVar[int]
    SOUND_FIELD_NUMBER: _ClassVar[int]
    UTTERANCE_FIELD_NUMBER: _ClassVar[int]
    SPOKEN_FIELD_NUMBER: _ClassVar[int]
    ENROLL_FIELD_NUMBER: _ClassVar[int]
    HEARING_FIELD_NUMBER: _ClassVar[int]
    status: Status
    heard: Heard
    ignored: Ignored
    level: Level
    partial: Partial
    robot_speaker: SpeakerFrame
    ctrl: RobotAudioCtrl
    say: SayProgress
    interrupted: Interrupted
    sound: RobotSound
    utterance: Utterance
    spoken: ReplySpoken
    enroll: EnrollProgress
    hearing: Hearing
    def __init__(self, status: _Optional[_Union[Status, _Mapping]] = ..., heard: _Optional[_Union[Heard, _Mapping]] = ..., ignored: _Optional[_Union[Ignored, _Mapping]] = ..., level: _Optional[_Union[Level, _Mapping]] = ..., partial: _Optional[_Union[Partial, _Mapping]] = ..., robot_speaker: _Optional[_Union[SpeakerFrame, _Mapping]] = ..., ctrl: _Optional[_Union[RobotAudioCtrl, _Mapping]] = ..., say: _Optional[_Union[SayProgress, _Mapping]] = ..., interrupted: _Optional[_Union[Interrupted, _Mapping]] = ..., sound: _Optional[_Union[RobotSound, _Mapping]] = ..., utterance: _Optional[_Union[Utterance, _Mapping]] = ..., spoken: _Optional[_Union[ReplySpoken, _Mapping]] = ..., enroll: _Optional[_Union[EnrollProgress, _Mapping]] = ..., hearing: _Optional[_Union[Hearing, _Mapping]] = ...) -> None: ...

class Status(_message.Message):
    __slots__ = ("state", "muted", "error", "fix", "stt", "tts", "listen_s", "hearing", "asked", "device")
    class State(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        STATE_UNSPECIFIED: _ClassVar[Status.State]
        STARTING: _ClassVar[Status.State]
        IDLE: _ClassVar[Status.State]
        LISTENING: _ClassVar[Status.State]
        THINKING: _ClassVar[Status.State]
        SPEAKING: _ClassVar[Status.State]
        STOPPED: _ClassVar[Status.State]
        ERROR: _ClassVar[Status.State]
    STATE_UNSPECIFIED: Status.State
    STARTING: Status.State
    IDLE: Status.State
    LISTENING: Status.State
    THINKING: Status.State
    SPEAKING: Status.State
    STOPPED: Status.State
    ERROR: Status.State
    STATE_FIELD_NUMBER: _ClassVar[int]
    MUTED_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    FIX_FIELD_NUMBER: _ClassVar[int]
    STT_FIELD_NUMBER: _ClassVar[int]
    TTS_FIELD_NUMBER: _ClassVar[int]
    LISTEN_S_FIELD_NUMBER: _ClassVar[int]
    HEARING_FIELD_NUMBER: _ClassVar[int]
    ASKED_FIELD_NUMBER: _ClassVar[int]
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    state: Status.State
    muted: bool
    error: str
    fix: str
    stt: str
    tts: str
    listen_s: float
    hearing: bool
    asked: bool
    device: str
    def __init__(self, state: _Optional[_Union[Status.State, str]] = ..., muted: _Optional[bool] = ..., error: _Optional[str] = ..., fix: _Optional[str] = ..., stt: _Optional[str] = ..., tts: _Optional[str] = ..., listen_s: _Optional[float] = ..., hearing: _Optional[bool] = ..., asked: _Optional[bool] = ..., device: _Optional[str] = ...) -> None: ...

class Heard(_message.Message):
    __slots__ = ("uid", "text", "raw", "language", "source", "latency", "wall_time", "continues", "speaker", "speaker_score")
    class LatencyEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: float
        def __init__(self, key: _Optional[str] = ..., value: _Optional[float] = ...) -> None: ...
    UID_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    RAW_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    SOURCE_FIELD_NUMBER: _ClassVar[int]
    LATENCY_FIELD_NUMBER: _ClassVar[int]
    WALL_TIME_FIELD_NUMBER: _ClassVar[int]
    CONTINUES_FIELD_NUMBER: _ClassVar[int]
    SPEAKER_FIELD_NUMBER: _ClassVar[int]
    SPEAKER_SCORE_FIELD_NUMBER: _ClassVar[int]
    uid: int
    text: str
    raw: str
    language: str
    source: str
    latency: _containers.ScalarMap[str, float]
    wall_time: float
    continues: _containers.RepeatedScalarFieldContainer[int]
    speaker: str
    speaker_score: float
    def __init__(self, uid: _Optional[int] = ..., text: _Optional[str] = ..., raw: _Optional[str] = ..., language: _Optional[str] = ..., source: _Optional[str] = ..., latency: _Optional[_Mapping[str, float]] = ..., wall_time: _Optional[float] = ..., continues: _Optional[_Iterable[int]] = ..., speaker: _Optional[str] = ..., speaker_score: _Optional[float] = ...) -> None: ...

class EnrollProgress(_message.Message):
    __slots__ = ("state", "index", "text", "match", "dbfs", "message", "result", "seconds")
    STATE_FIELD_NUMBER: _ClassVar[int]
    INDEX_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    MATCH_FIELD_NUMBER: _ClassVar[int]
    DBFS_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    RESULT_FIELD_NUMBER: _ClassVar[int]
    SECONDS_FIELD_NUMBER: _ClassVar[int]
    state: str
    index: int
    text: str
    match: float
    dbfs: float
    message: str
    result: EnrollResult
    seconds: float
    def __init__(self, state: _Optional[str] = ..., index: _Optional[int] = ..., text: _Optional[str] = ..., match: _Optional[float] = ..., dbfs: _Optional[float] = ..., message: _Optional[str] = ..., result: _Optional[_Union[EnrollResult, _Mapping]] = ..., seconds: _Optional[float] = ...) -> None: ...

class EnrollResult(_message.Message):
    __slots__ = ("name", "device", "noise_dbfs", "speech_dbfs", "vad_min_dbfs", "speech_min_dbfs", "vad_threshold", "voiceprint", "warnings", "transcripts")
    NAME_FIELD_NUMBER: _ClassVar[int]
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    NOISE_DBFS_FIELD_NUMBER: _ClassVar[int]
    SPEECH_DBFS_FIELD_NUMBER: _ClassVar[int]
    VAD_MIN_DBFS_FIELD_NUMBER: _ClassVar[int]
    SPEECH_MIN_DBFS_FIELD_NUMBER: _ClassVar[int]
    VAD_THRESHOLD_FIELD_NUMBER: _ClassVar[int]
    VOICEPRINT_FIELD_NUMBER: _ClassVar[int]
    WARNINGS_FIELD_NUMBER: _ClassVar[int]
    TRANSCRIPTS_FIELD_NUMBER: _ClassVar[int]
    name: str
    device: AudioDeviceProfile
    noise_dbfs: float
    speech_dbfs: float
    vad_min_dbfs: float
    speech_min_dbfs: float
    vad_threshold: float
    voiceprint: Voiceprint
    warnings: _containers.RepeatedScalarFieldContainer[str]
    transcripts: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, name: _Optional[str] = ..., device: _Optional[_Union[AudioDeviceProfile, _Mapping]] = ..., noise_dbfs: _Optional[float] = ..., speech_dbfs: _Optional[float] = ..., vad_min_dbfs: _Optional[float] = ..., speech_min_dbfs: _Optional[float] = ..., vad_threshold: _Optional[float] = ..., voiceprint: _Optional[_Union[Voiceprint, _Mapping]] = ..., warnings: _Optional[_Iterable[str]] = ..., transcripts: _Optional[_Iterable[str]] = ...) -> None: ...

class Ignored(_message.Message):
    __slots__ = ("text", "reason", "dbfs")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    DBFS_FIELD_NUMBER: _ClassVar[int]
    text: str
    reason: str
    dbfs: float
    def __init__(self, text: _Optional[str] = ..., reason: _Optional[str] = ..., dbfs: _Optional[float] = ...) -> None: ...

class Level(_message.Message):
    __slots__ = ("mic", "speech", "gated", "dbfs")
    MIC_FIELD_NUMBER: _ClassVar[int]
    SPEECH_FIELD_NUMBER: _ClassVar[int]
    GATED_FIELD_NUMBER: _ClassVar[int]
    DBFS_FIELD_NUMBER: _ClassVar[int]
    mic: float
    speech: bool
    gated: bool
    dbfs: float
    def __init__(self, mic: _Optional[float] = ..., speech: _Optional[bool] = ..., gated: _Optional[bool] = ..., dbfs: _Optional[float] = ...) -> None: ...

class Hearing(_message.Message):
    __slots__ = ("kind", "uid", "wall_time", "vad", "turn", "complete", "probability", "turn_s", "duration_s", "dbfs", "stt", "stt_s", "speculative", "text", "language", "language_prob", "wake", "speaker", "speaker_score", "outcome", "reason")
    KIND_FIELD_NUMBER: _ClassVar[int]
    UID_FIELD_NUMBER: _ClassVar[int]
    WALL_TIME_FIELD_NUMBER: _ClassVar[int]
    VAD_FIELD_NUMBER: _ClassVar[int]
    TURN_FIELD_NUMBER: _ClassVar[int]
    COMPLETE_FIELD_NUMBER: _ClassVar[int]
    PROBABILITY_FIELD_NUMBER: _ClassVar[int]
    TURN_S_FIELD_NUMBER: _ClassVar[int]
    DURATION_S_FIELD_NUMBER: _ClassVar[int]
    DBFS_FIELD_NUMBER: _ClassVar[int]
    STT_FIELD_NUMBER: _ClassVar[int]
    STT_S_FIELD_NUMBER: _ClassVar[int]
    SPECULATIVE_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_PROB_FIELD_NUMBER: _ClassVar[int]
    WAKE_FIELD_NUMBER: _ClassVar[int]
    SPEAKER_FIELD_NUMBER: _ClassVar[int]
    SPEAKER_SCORE_FIELD_NUMBER: _ClassVar[int]
    OUTCOME_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    kind: str
    uid: int
    wall_time: float
    vad: str
    turn: str
    complete: bool
    probability: float
    turn_s: float
    duration_s: float
    dbfs: float
    stt: str
    stt_s: float
    speculative: bool
    text: str
    language: str
    language_prob: float
    wake: bool
    speaker: str
    speaker_score: float
    outcome: str
    reason: str
    def __init__(self, kind: _Optional[str] = ..., uid: _Optional[int] = ..., wall_time: _Optional[float] = ..., vad: _Optional[str] = ..., turn: _Optional[str] = ..., complete: _Optional[bool] = ..., probability: _Optional[float] = ..., turn_s: _Optional[float] = ..., duration_s: _Optional[float] = ..., dbfs: _Optional[float] = ..., stt: _Optional[str] = ..., stt_s: _Optional[float] = ..., speculative: _Optional[bool] = ..., text: _Optional[str] = ..., language: _Optional[str] = ..., language_prob: _Optional[float] = ..., wake: _Optional[bool] = ..., speaker: _Optional[str] = ..., speaker_score: _Optional[float] = ..., outcome: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class Partial(_message.Message):
    __slots__ = ("uid", "text")
    UID_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    uid: int
    text: str
    def __init__(self, uid: _Optional[int] = ..., text: _Optional[str] = ...) -> None: ...

class Utterance(_message.Message):
    __slots__ = ("state", "uid")
    class State(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        STATE_UNSPECIFIED: _ClassVar[Utterance.State]
        START: _ClassVar[Utterance.State]
        END: _ClassVar[Utterance.State]
        DONE: _ClassVar[Utterance.State]
    STATE_UNSPECIFIED: Utterance.State
    START: Utterance.State
    END: Utterance.State
    DONE: Utterance.State
    STATE_FIELD_NUMBER: _ClassVar[int]
    UID_FIELD_NUMBER: _ClassVar[int]
    state: Utterance.State
    uid: int
    def __init__(self, state: _Optional[_Union[Utterance.State, str]] = ..., uid: _Optional[int] = ...) -> None: ...

class SpeakerFrame(_message.Message):
    __slots__ = ("device", "stream", "sample_index", "pcm")
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    STREAM_FIELD_NUMBER: _ClassVar[int]
    SAMPLE_INDEX_FIELD_NUMBER: _ClassVar[int]
    PCM_FIELD_NUMBER: _ClassVar[int]
    device: str
    stream: int
    sample_index: int
    pcm: bytes
    def __init__(self, device: _Optional[str] = ..., stream: _Optional[int] = ..., sample_index: _Optional[int] = ..., pcm: _Optional[bytes] = ...) -> None: ...

class RobotAudioCtrl(_message.Message):
    __slots__ = ("device", "command", "argument")
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    COMMAND_FIELD_NUMBER: _ClassVar[int]
    ARGUMENT_FIELD_NUMBER: _ClassVar[int]
    device: str
    command: int
    argument: int
    def __init__(self, device: _Optional[str] = ..., command: _Optional[int] = ..., argument: _Optional[int] = ...) -> None: ...

class RobotSound(_message.Message):
    __slots__ = ("device", "id")
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    ID_FIELD_NUMBER: _ClassVar[int]
    device: str
    id: int
    def __init__(self, device: _Optional[str] = ..., id: _Optional[int] = ...) -> None: ...

class SayProgress(_message.Message):
    __slots__ = ("reply_id", "text", "seconds", "envelope")
    REPLY_ID_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    SECONDS_FIELD_NUMBER: _ClassVar[int]
    ENVELOPE_FIELD_NUMBER: _ClassVar[int]
    reply_id: int
    text: str
    seconds: float
    envelope: _containers.RepeatedScalarFieldContainer[float]
    def __init__(self, reply_id: _Optional[int] = ..., text: _Optional[str] = ..., seconds: _Optional[float] = ..., envelope: _Optional[_Iterable[float]] = ...) -> None: ...

class Interrupted(_message.Message):
    __slots__ = ("reply_id", "reason", "utterance_uid")
    REPLY_ID_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    UTTERANCE_UID_FIELD_NUMBER: _ClassVar[int]
    reply_id: int
    reason: str
    utterance_uid: int
    def __init__(self, reply_id: _Optional[int] = ..., reason: _Optional[str] = ..., utterance_uid: _Optional[int] = ...) -> None: ...

class ReplySpoken(_message.Message):
    __slots__ = ("reply_id", "latency", "interrupted", "text", "utterance_uid")
    class LatencyEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: float
        def __init__(self, key: _Optional[str] = ..., value: _Optional[float] = ...) -> None: ...
    REPLY_ID_FIELD_NUMBER: _ClassVar[int]
    LATENCY_FIELD_NUMBER: _ClassVar[int]
    INTERRUPTED_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    UTTERANCE_UID_FIELD_NUMBER: _ClassVar[int]
    reply_id: int
    latency: _containers.ScalarMap[str, float]
    interrupted: bool
    text: str
    utterance_uid: int
    def __init__(self, reply_id: _Optional[int] = ..., latency: _Optional[_Mapping[str, float]] = ..., interrupted: _Optional[bool] = ..., text: _Optional[str] = ..., utterance_uid: _Optional[int] = ...) -> None: ...

class AudioClip(_message.Message):
    __slots__ = ("pcm", "language")
    PCM_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    pcm: bytes
    language: str
    def __init__(self, pcm: _Optional[bytes] = ..., language: _Optional[str] = ...) -> None: ...

class Transcript(_message.Message):
    __slots__ = ("text", "language", "language_prob", "no_speech_prob", "avg_logprob", "compression_ratio", "seconds", "rejected")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_PROB_FIELD_NUMBER: _ClassVar[int]
    NO_SPEECH_PROB_FIELD_NUMBER: _ClassVar[int]
    AVG_LOGPROB_FIELD_NUMBER: _ClassVar[int]
    COMPRESSION_RATIO_FIELD_NUMBER: _ClassVar[int]
    SECONDS_FIELD_NUMBER: _ClassVar[int]
    REJECTED_FIELD_NUMBER: _ClassVar[int]
    text: str
    language: str
    language_prob: float
    no_speech_prob: float
    avg_logprob: float
    compression_ratio: float
    seconds: float
    rejected: str
    def __init__(self, text: _Optional[str] = ..., language: _Optional[str] = ..., language_prob: _Optional[float] = ..., no_speech_prob: _Optional[float] = ..., avg_logprob: _Optional[float] = ..., compression_ratio: _Optional[float] = ..., seconds: _Optional[float] = ..., rejected: _Optional[str] = ...) -> None: ...

class OptionsRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class VoiceOptions(_message.Message):
    __slots__ = ("stt", "stt_models", "tts", "voices", "languages", "ears")
    STT_FIELD_NUMBER: _ClassVar[int]
    STT_MODELS_FIELD_NUMBER: _ClassVar[int]
    TTS_FIELD_NUMBER: _ClassVar[int]
    VOICES_FIELD_NUMBER: _ClassVar[int]
    LANGUAGES_FIELD_NUMBER: _ClassVar[int]
    EARS_FIELD_NUMBER: _ClassVar[int]
    stt: _containers.RepeatedCompositeFieldContainer[Backend]
    stt_models: _containers.RepeatedScalarFieldContainer[str]
    tts: _containers.RepeatedCompositeFieldContainer[Backend]
    voices: _containers.RepeatedCompositeFieldContainer[VoiceChoice]
    languages: _containers.RepeatedScalarFieldContainer[str]
    ears: _containers.RepeatedCompositeFieldContainer[Backend]
    def __init__(self, stt: _Optional[_Iterable[_Union[Backend, _Mapping]]] = ..., stt_models: _Optional[_Iterable[str]] = ..., tts: _Optional[_Iterable[_Union[Backend, _Mapping]]] = ..., voices: _Optional[_Iterable[_Union[VoiceChoice, _Mapping]]] = ..., languages: _Optional[_Iterable[str]] = ..., ears: _Optional[_Iterable[_Union[Backend, _Mapping]]] = ...) -> None: ...

class Backend(_message.Message):
    __slots__ = ("name", "installed", "why")
    NAME_FIELD_NUMBER: _ClassVar[int]
    INSTALLED_FIELD_NUMBER: _ClassVar[int]
    WHY_FIELD_NUMBER: _ClassVar[int]
    name: str
    installed: bool
    why: str
    def __init__(self, name: _Optional[str] = ..., installed: _Optional[bool] = ..., why: _Optional[str] = ...) -> None: ...

class VoiceChoice(_message.Message):
    __slots__ = ("id", "engine", "language", "installed", "locale")
    ID_FIELD_NUMBER: _ClassVar[int]
    ENGINE_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_FIELD_NUMBER: _ClassVar[int]
    INSTALLED_FIELD_NUMBER: _ClassVar[int]
    LOCALE_FIELD_NUMBER: _ClassVar[int]
    id: str
    engine: str
    language: str
    installed: bool
    locale: str
    def __init__(self, id: _Optional[str] = ..., engine: _Optional[str] = ..., language: _Optional[str] = ..., installed: _Optional[bool] = ..., locale: _Optional[str] = ...) -> None: ...
