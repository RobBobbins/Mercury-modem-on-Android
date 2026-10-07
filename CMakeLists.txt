# Android (NDK) build of the Mercury HF modem command-line program.
# Mercury is GPL-3.0-or-later (see upstream/LICENSE); this build file is too.
# Source lists follow upstream/Makefile and the per-folder Makefiles of
# Mercury v1.9.17 (git b2ad7863). The upstream tree is not modified.
#
# Android choices: AAudio is the only sound system; no hamlib, no hidapi,
# no HERMES shared memory, no OpenSSL (the UI web server serves plain ws://).

cmake_minimum_required(VERSION 3.22)
project(mercury_android C)

set(UP ${CMAKE_CURRENT_SOURCE_DIR}/upstream)
set(GEN ${CMAKE_CURRENT_SOURCE_DIR}/gen)

set(FREEDV_SRCS
    freedv_api ofdm freedv_fsk cohpsk fsk kiss_fft freedv_1600 fdmdv
    codec2_fft codec2 pack kiss_fftr newamp1 quantise lsp lpc mbest
    phase sine varicode freedv_700 freedv_vhf_framing fmfsk filter
    freedv_data_channel ldpc_codes mpdecode_core interldpc golay23 linreg
    nlp codebooknewamp1 codebooknewamp1_energy codebooknewamp2 codebooknewamp2_energy
    postfilter interp codebook codebookd codebookge codebookjmv ofdm_mode
    HRA_112_112 HRA_56_56 HRAb_396_504 H_256_768_22 H_256_512_4 HRAa_1536_512
    H_128_256_5 H_2064_516_sparse H_4096_8192_3d H_16200_9720 H_1024_2048_4f
    H_212_158 phi0 gp_interleaver modem_stats)
list(TRANSFORM FREEDV_SRCS PREPEND ${UP}/modem/freedv/)
list(TRANSFORM FREEDV_SRCS APPEND .c)

set(MERCURY_SRCS
    main.c common/cfg_utils.c common/iniparser/iniparser.c common/iniparser/dictionary.c
    datalink_arq/arq.c datalink_arq/arq_trace.c datalink_arq/arq_tnc.c datalink_arq/arith.c
    datalink_arq/arq_channels.c datalink_arq/arq_fsm.c datalink_arq/arq_protocol.c
    datalink_arq/arq_timing.c datalink_arq/arq_modem.c
    datalink_broadcast/broadcast.c datalink_broadcast/kiss.c
    modem/modem.c modem/framer.c modem/channel_busy.c
    audioio/audioio.c audioio/resampler.c audioio/ffaudio/ffaudio/aaudio.c
    common/os_interop.c common/ring_buffer_posix.c common/shm_posix.c common/crc6.c
    common/hermes_log.c common/virtual_clock.c common/chan.c common/queue.c
    common/mercury_engine.c common/mercury_cli.c common/mercury_modes.c common/message_store.c
    data_interfaces/tcp_interfaces.c data_interfaces/net.c
    gui_interface/ui_communication.c gui_interface/ui_status.c gui_interface/ui_devices.c
    gui_interface/ui_history.c
    gui_interface/websocket/mercury_websocket.c gui_interface/websocket/ws_server.c
    gui_interface/websocket/ws_crypto.c gui_interface/websocket/ws_frame.c
    gui_interface/websocket/ws_json.c gui_interface/websocket/ws_tls.c
    radio_io/radio_io.c radio_io/serial_ptt.c radio_io/cm108_ptt.c)
list(TRANSFORM MERCURY_SRCS PREPEND ${UP}/)

set(PORT ${CMAKE_CURRENT_SOURCE_DIR}/port)

add_executable(mercury ${MERCURY_SRCS} ${FREEDV_SRCS} ${GEN}/web_packed.c ${PORT}/android_port.c)

# Android gaps (exp10f, shm_open) are filled by port/android_port.h, and the
# two ring-buffer readers aaudio.c needs by port/ffring_ext.h.
target_compile_options(mercury PRIVATE -include ${PORT}/android_port.h)
set_source_files_properties(${UP}/audioio/ffaudio/ffaudio/aaudio.c
    PROPERTIES COMPILE_OPTIONS "-include;${PORT}/ffring_ext.h")

target_include_directories(mercury PRIVATE
    ${UP} ${UP}/modem/freedv ${UP}/modem ${UP}/datalink_broadcast ${UP}/data_interfaces
    ${UP}/datalink_arq ${UP}/audioio ${UP}/audioio/ffaudio
    ${UP}/common ${UP}/common/iniparser ${UP}/gui_interface ${UP}/gui_interface/websocket
    ${UP}/radio_io)

target_compile_options(mercury PRIVATE -O2 -std=gnu11 -pthread -Wno-strict-overflow -Wno-unused)
target_compile_definitions(mercury PRIVATE _GNU_SOURCE GIT_HASH="b2ad7863")
target_link_libraries(mercury PRIVATE aaudio log m)
