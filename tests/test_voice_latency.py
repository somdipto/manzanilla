"""Known-clock signals exercise real PCM detection, not provider/device mocks.

Synthetic sounds are NOT speech or quality evidence; test labels only exercise
separation of activity detection from explicitly supplied human evaluation.
"""
from array import array
from copy import deepcopy
import math
import sys
import wave

import pytest

from tools import voice_latency as latency


def recording(tmp_path, name='capture.wav', *, regions=None, duration=2, rate=16000,
              channels=2, width=2, floor=0):
    """Build independent deterministic channel signals, optionally with noise."""
    if regions is None:
        regions = {0: [(.2, .6, 12000)], 1: [(1, 1.4, 12000)]}
    frames = round(duration * rate)
    samples = array('h')
    for index in range(frames):
        for channel in range(channels):
            value = floor if index % 2 else -floor
            for start, end, amplitude in regions.get(channel, []):
                if round(start * rate) <= index < round(end * rate):
                    value += amplitude if index % 2 else -amplitude
            samples.append(value)
    if sys.byteorder != 'little':
        samples.byteswap()
    path = tmp_path / name
    with wave.open(str(path), 'wb') as wav:
        wav.setparams((channels, width, rate, frames, 'NONE', 'not compressed'))
        # Non-16-bit files intentionally contain arbitrary bytes for format rejection.
        wav.writeframes(samples.tobytes())
    return path


def analysis_for(tmp_path, offset=.4, *, silent=False, name='trial'):
    path = tmp_path / (name + '.wav')
    calibration = latency.calibrate(path, offset)
    if silent:
        path = recording(tmp_path, name + '-silent.wav', regions={0: [(.2, .6, 12000)]})
    duration = latency.load_pcm(path, 2)['duration_s']
    return latency.analyze(path, (0, duration), (0, duration), (0, .1), (0, .1),
                           label='unreviewed' if silent else 'correct',
                           naturalness='unreviewed', calibration=calibration)


def scheduled(index, arm='A', condition='warmed', direction='en-es'):
    return {'fixture_id': str(index), 'repetition': 1, 'direction': direction,
            'condition': condition, 'arm': arm}


@pytest.mark.parametrize('offset', [0, .4, 1.237, -.1, -.3])
def test_known_offsets_and_complete_boundaries_share_one_clock(tmp_path, offset):
    path = tmp_path / 'calibration.wav'
    report = latency.calibrate(path, offset)
    assert report['passed'] is True
    for key in ('input_end_s', 'output_onset_s', 'output_end_s', 'offset_s'):
        assert report['measured'][key] == pytest.approx(report['expected'][key], abs=report['uncertainty_s'])
    assert report['expected']['offset_s'] == pytest.approx(offset, abs=1 / 16000)
    assert report['measured']['input_end_s'] == pytest.approx(.6)
    assert 'not physical' in report['kind']


def test_silence_is_no_response_not_an_onset(tmp_path):
    report = analysis_for(tmp_path, silent=True)
    assert report['capture_valid'] is True
    assert report['detection']['reference']['last_activity_end_s'] == pytest.approx(.6)
    assert report['detection']['output']['first_activity_s'] is None
    assert report['detection']['output']['last_activity_end_s'] is None
    assert report['detection']['candidate_latency_s'] is None
    assert report['evaluation']['accepted_latency_s'] is None


def test_detection_is_not_automatic_correctness_or_speech(tmp_path):
    path = recording(tmp_path)
    report = latency.analyze(path, (0, 2), (0, 2), (0, .1), (0, .1))
    assert report['detection']['candidate_latency_s'] == pytest.approx(.4)
    assert report['evaluation']['input_last_speech_end_s'] is None
    assert report['evaluation']['accepted_output_onset_s'] is None
    assert report['evaluation']['complete_output_end_s'] is None
    assert report['evaluation']['naturalness'] == 'unreviewed'
    assert report['synthetic_detector_calibration_checked'] is False


def test_explicit_windows_exclude_greeting_and_preserve_input_pauses(tmp_path):
    path = recording(tmp_path, duration=3, regions={
        0: [(.4, .6, 12000), (.8, 1, 12000)],
        1: [(.1, .3, 12000), (1.6, 1.8, 12000), (2, 2.2, 12000)],
    })
    report = latency.analyze(path, (.3, 1.2), (1.2, 2.5), (0, .05), (0, .05), label='correct')
    assert report['evaluation']['input_last_speech_end_s'] == pytest.approx(1)
    assert report['evaluation']['accepted_output_onset_s'] == pytest.approx(1.6)
    assert report['evaluation']['complete_output_end_s'] == pytest.approx(2.2)
    assert report['evaluation']['accepted_latency_s'] == pytest.approx(.6)
    assert len(report['detection']['reference']['activity_regions_s']) == 2


def test_noise_and_short_clicks_are_not_sustained_activity(tmp_path):
    path = recording(tmp_path, floor=100, regions={
        0: [(.2, .6, 12000)], 1: [(.7, .71, 12000), (1, 1.4, 12000)],
    })
    report = latency.analyze(path, (0, 2), (0, 2), (0, .1), (0, .1))
    assert report['detection']['output']['first_activity_s'] == pytest.approx(1)
    assert report['detection']['output']['noise_rms'] == pytest.approx(100 / 32768)


def test_noise_threshold_requires_margin_and_rejects_contaminated_quiet_window(tmp_path):
    path = recording(tmp_path, floor=400)
    pcm = latency.load_pcm(path, 2)
    with pytest.raises(ValueError, match='noise RMS'):
        latency.detect(pcm, 0, (0, 2), (0, .1))
    with pytest.raises(ValueError, match='noise RMS'):
        latency.detect(pcm, 0, (0, 2), (.3, .4), threshold=.1)


@pytest.mark.parametrize('window', [(-1, 1), (0, 3), (1, 1), (1, 0), (0, math.inf), (0, math.nan)])
def test_invalid_analysis_windows_are_rejected(tmp_path, window):
    pcm = latency.load_pcm(recording(tmp_path), 2)
    with pytest.raises(ValueError):
        latency.detect(pcm, 0, window, (0, .1))


@pytest.mark.parametrize('window', [(.2, .8), (0, .6), (.201, .599)])
def test_clipped_activity_at_either_window_edge_is_not_complete(tmp_path, window):
    pcm = latency.load_pcm(recording(tmp_path), 2)
    with pytest.raises(ValueError, match='boundary'):
        latency.detect(pcm, 0, window, (0, .1))


def test_even_a_short_boundary_click_requires_a_wider_window(tmp_path):
    path = recording(tmp_path, regions={0: [(.2, .205, 12000)]})
    with pytest.raises(ValueError, match='boundary'):
        latency.detect(latency.load_pcm(path, 2), 0, (.2, .5), (0, .1))


def test_subframe_boundaries_have_reported_quantization_error(tmp_path):
    path = recording(tmp_path, regions={0: [(.203, .607, 12000)], 1: [(1.237, 1.643, 12000)]})
    report = latency.analyze(path, (0, 2), (0, 2), (0, .1), (0, .1))
    detected = report['detection']
    assert abs(detected['reference']['last_activity_end_s'] - .607) <= .01
    assert abs(detected['output']['first_activity_s'] - 1.237) <= .01
    assert abs(detected['output']['last_activity_end_s'] - 1.643) <= .01
    assert detected['output']['resolution_s'] == .01


def test_silent_reference_and_correct_silent_output_are_invalid(tmp_path):
    path = recording(tmp_path, regions={})
    with pytest.raises(ValueError, match='reference'):
        latency.analyze(path, (0, 2), (0, 2), (0, .1), (0, .1))
    path = recording(tmp_path, regions={0: [(.2, .6, 12000)]})
    with pytest.raises(ValueError, match='silent output'):
        latency.analyze(path, (0, 2), (0, 2), (0, .1), (0, .1), label='correct')


@pytest.mark.parametrize('threshold,frame_ms,min_ms', [(0, 10, 20), (1, 10, 20), (math.nan, 10, 20), (.02, 0, 20), (.02, 30, 20)])
def test_invalid_detector_settings_are_rejected(tmp_path, threshold, frame_ms, min_ms):
    pcm = latency.load_pcm(recording(tmp_path), 2)
    with pytest.raises(ValueError):
        latency.detect(pcm, 0, (0, 2), (0, .1), threshold, frame_ms, min_ms)


@pytest.mark.parametrize('channels,width,rate', [(1, 2, 16000), (2, 1, 16000), (2, 3, 16000), (2, 2, 4000)])
def test_unsupported_pcm_is_rejected(tmp_path, channels, width, rate):
    path = recording(tmp_path, channels=channels, width=width, rate=rate)
    with pytest.raises(ValueError):
        latency.load_pcm(path, 2)


def test_empty_corrupt_and_truncated_recordings_are_rejected(tmp_path):
    empty = recording(tmp_path, duration=0)
    with pytest.raises(ValueError, match='nonempty'):
        latency.load_pcm(empty, 2)
    corrupt = tmp_path / 'corrupt.wav'
    corrupt.write_bytes(b'not a wave recording')
    with pytest.raises(ValueError, match='invalid PCM'):
        latency.load_pcm(corrupt, 2)
    path = recording(tmp_path, name='truncated.wav')
    path.write_bytes(path.read_bytes()[:-400])
    with pytest.raises(ValueError, match='truncated'):
        latency.load_pcm(path, 2)


def test_calibration_mismatch_or_failed_offsets_are_rejected(tmp_path):
    path = tmp_path / 'cal.wav'
    calibration = latency.calibrate(path)
    duration = latency.load_pcm(path, 2)['duration_s']
    arguments = (path, (0, duration), (0, duration), (0, .1), (0, .1))
    with pytest.raises(ValueError, match='same detector'):
        latency.analyze(*arguments, frame_ms=5, calibration=calibration)
    invalid = deepcopy(calibration)
    invalid['measured']['offset_s'] += .1
    with pytest.raises(ValueError, match='uncertainty'):
        latency.analyze(*arguments, calibration=invalid)
    with pytest.raises(ValueError, match='offset'):
        latency.calibrate(path, -.4)


def test_failure_aware_nearest_rank_plan_population(tmp_path):
    fast = analysis_for(tmp_path, 1, name='fast')
    slow = analysis_for(tmp_path, 2.5, name='slow')
    silent = analysis_for(tmp_path, silent=True, name='timeout')
    schedule = [scheduled(index) for index in range(1000)]
    trials = [{**item, 'status': 'success' if index < 985 else 'timeout',
               'analysis': fast if index < 940 else slow if index < 985 else silent}
              for index, item in enumerate(schedule)]
    report = latency.summarize({'scheduled': schedule, 'trials': trials})
    group = report['groups'][0]
    assert len(report['trials']) == 1000
    assert group['successful_only_conditional']['p95_s'] == pytest.approx(1)
    assert group['all_scheduled_qualification']['p95_s'] == pytest.approx(2.5)
    assert group['warmed_day_latency_threshold'] == 'not_met'
    assert group['failure_rate'] == .015
    assert group['status_counts'] == {'success': 985, 'timeout': 15}
    assert report['trials'][-1]['qualification_score_s'] == 15
    assert report['trials'][-1]['analysis']['detection']['output']['first_activity_s'] is None


@pytest.mark.parametrize('status', ['timeout', 'wrong_translation', 'duplicate', 'provider_error', 'failed'])
def test_completed_unsuccessful_turns_keep_categories_and_penalty(tmp_path, status):
    item = scheduled(1)
    analysis = analysis_for(tmp_path, silent=True)
    report = latency.summarize({'scheduled': [item], 'trials': [{**item, 'status': status, 'analysis': analysis}]})
    assert report['trials'][0]['effective_status'] == status
    assert report['trials'][0]['qualification_score_s'] == 15
    assert report['groups'][0]['status_counts'] == {status: 1}


def test_failed_paired_arms_are_not_complete_case_filtered(tmp_path):
    fast = analysis_for(tmp_path, 1, name='fast')
    slow = analysis_for(tmp_path, 2.5, name='slow')
    silent = analysis_for(tmp_path, silent=True, name='silent')
    schedule = [scheduled(index, arm) for index in range(3) for arm in ('A', 'B')]
    results = [(fast, 'success'), (silent, 'timeout'), (silent, 'provider_error'),
               (fast, 'success'), (slow, 'success'), (slow, 'success')]
    trials = [{**item, 'analysis': value, 'status': status}
              for item, (value, status) in zip(schedule, results)]
    report = latency.summarize({'scheduled': schedule, 'trials': trials})
    assert report['paired_data_complete'] is True
    for row, expected in zip(report['paired_blocks'], [(1, 15), (15, 1), (2.5, 2.5)]):
        assert (row['A_score_s'], row['B_score_s']) == pytest.approx(expected)
    assert len(report['trials']) == 6


def test_unrun_and_technically_invalid_trials_remain_unscored_and_block(tmp_path):
    valid = analysis_for(tmp_path)
    schedule = [scheduled(1, arm) for arm in ('A', 'B')] + [scheduled(2, arm) for arm in ('A', 'B')]
    trials = [{**schedule[0], 'status': 'success', 'analysis': valid},
              {**schedule[2], 'status': 'invalid', 'analysis': {'capture_valid': False}},
              {**schedule[3], 'status': 'timeout'}]
    report = latency.summarize({'scheduled': schedule, 'trials': trials})
    assert len(report['trials']) == 4
    assert report['paired_data_complete'] is False
    assert [row['effective_status'] for row in report['trials']] == ['success', 'missing', 'invalid', 'invalid']
    assert [row['qualification_score_s'] for row in report['trials']][1:] == [None, None, None]
    assert all(group['warmed_day_latency_threshold'] == 'blocked' for group in report['groups'])
    assert all(group['all_scheduled_qualification']['p95_s'] is None for group in report['groups'])


def test_directions_conditions_and_unreviewed_success_are_never_pooled(tmp_path):
    reviewed = analysis_for(tmp_path)
    unreviewed = deepcopy(reviewed)
    unreviewed['evaluation']['label'] = 'unreviewed'
    unreviewed['evaluation']['accepted_latency_s'] = None
    schedule = [scheduled(1), scheduled(2, direction='es-en'), scheduled(3, condition='greeting')]
    trials = [{**item, 'status': 'success', 'analysis': unreviewed if index == 1 else reviewed}
              for index, item in enumerate(schedule)]
    report = latency.summarize({'scheduled': schedule, 'trials': trials})
    assert len(report['groups']) == 3
    assert report['trials'][1]['effective_status'] == 'unreviewed_success'
    assert report['qualification'].startswith('blocked:')


def test_overlap_is_retained_only_as_separate_interruption_condition(tmp_path):
    overlap = analysis_for(tmp_path, -.1)
    schedule = [scheduled(1), scheduled(2, condition='interruption')]
    trials = [{**item, 'status': 'success', 'analysis': overlap} for item in schedule]
    report = latency.summarize({'scheduled': schedule, 'trials': trials})
    assert report['trials'][0]['effective_status'] == 'invalid'
    assert report['trials'][1]['qualification_score_s'] == pytest.approx(-.1)
    assert all(group['warmed_day_latency_threshold'] == 'blocked' for group in report['groups'])


def test_schedule_rejects_duplicate_or_unscheduled_observations(tmp_path):
    item = scheduled(1)
    with pytest.raises(ValueError, match='duplicate scheduled'):
        latency.summarize({'scheduled': [item, item], 'trials': []})
    with pytest.raises(ValueError, match='unscheduled'):
        latency.summarize({'scheduled': [item], 'trials': [{**scheduled(2), 'status': 'missing'}]})
    with pytest.raises(ValueError, match='duplicate trial'):
        latency.summarize({'scheduled': [item], 'trials': [{**item, 'status': 'missing'}] * 2})


def test_unusable_calibration_signal_fails_explicitly(tmp_path):
    with pytest.raises(ValueError, match='below the detector threshold'):
        latency.calibrate(tmp_path / 'quiet-cal.wav', threshold=.9)


@pytest.mark.parametrize('field,value', [
    ('accepted_latency_s', math.nan),
    ('accepted_latency_s', 7),
    ('complete_output_end_s', .1),
])
def test_malformed_success_measurements_are_retained_as_invalid(tmp_path, field, value):
    analysis = analysis_for(tmp_path)
    analysis['evaluation'][field] = value
    item = scheduled(1)
    report = latency.summarize({
        'scheduled': [item],
        'trials': [{**item, 'status': 'success', 'analysis': analysis}],
    })
    assert report['trials'][0]['reported_status'] == 'success'
    assert report['trials'][0]['effective_status'] == 'invalid'
    assert report['trials'][0]['qualification_score_s'] is None
    assert report['groups'][0]['warmed_day_latency_threshold'] == 'blocked'
