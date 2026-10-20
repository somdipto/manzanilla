#!/usr/bin/env python3
"""Offline acoustic evidence, never a device runner or speech recognizer.

Capture one unprocessed stereo PCM16 WAV on ONE recorder: channel 1 is the
actual played synthetic input; channel 2 is the actual tablet speaker. Fix
placement/volume; measure physical delay, noise and crosstalk separately. Do
not use decoded audio, captions, or Android timestamps as recorder timestamps.
Use separate windows/records for greeting, startup, warmed serial turns,
interruptions and recovery. Listen to boundaries and inspect waveforms.

Examples (all offline, no network or audio-device dependency)::

    python3 tools/voice_latency.py calibrate /tmp/cal.wav --report /tmp/cal.json
    python3 tools/voice_latency.py source synthetic-phrase.wav --emit /tmp/replay.wav
    python3 tools/voice_latency.py analyze capture.wav --input-window 1 3 \
        --output-window 3 8 --input-noise-window 0 .5 --output-noise-window 0 .5 \
        --calibration /tmp/cal.json --label correct --naturalness tie > /tmp/turn.json
    python3 tools/voice_latency.py summarize schedule.json

An analysis window needs quiet guard frames on both sides. A correct label
means a bilingual reviewer confirmed the input boundary is speech and the
FIRST detected output is the expected translation, not echo/greeting/error.
Multiple or ambiguous responses must be labelled unsuccessful. Naturalness
needs independent blind bilingual review; no signal detector can assess it.
Calibration here tests the algorithm on synthetic sounds, NOT the real setup.

Summary JSON schema: {"scheduled": [identity, ...], "trials": [result, ...]}.
Identity: fixture_id (str), repetition (positive int), direction (str),
condition (startup|greeting|warmed|interruption|recovery), arm (A|B).
Result adds status (success|timeout|wrong_translation|duplicate|provider_error|
failed|missing|invalid), and analysis (the analyze JSON object or its path,
relative to schedule.json). Every completed trial, including timeout, needs a
valid capture; omit an unrun trial or mark it missing. Never omit its schedule.
Only explicit, reviewed success uses detected latency; other completed failures
score 15 seconds, NOT an asserted acoustic arrival time. Missing/invalid data
blocks qualification and is never a completed failure. Keep both A/B arms in
the schedule. Summary is descriptive: it cannot certify corpus coverage,
quality, physical calibration, or the plan's unconfirmed paired-bootstrap gate.
"""

import argparse
from array import array
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import sys
import wave


CONDITIONS = {'startup', 'greeting', 'warmed', 'interruption', 'recovery'}
STATUSES = {'success', 'timeout', 'wrong_translation', 'duplicate',
            'provider_error', 'failed', 'missing', 'invalid'}
TIMEOUT_SECONDS = 15.0


def number(value, name):
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value):
        raise ValueError(name + ' must be finite')
    return float(value)


def load_pcm(path, channels):
    """Only PCM16 is supported; reject truncated/empty/unsupported recordings."""
    path = Path(path)
    try:
        with wave.open(str(path), 'rb') as wav:
            if wav.getcomptype() != 'NONE' or wav.getsampwidth() != 2:
                raise ValueError('expected uncompressed signed 16-bit PCM WAV')
            if wav.getnchannels() != channels:
                raise ValueError(f'expected {channels} channels')
            rate, frames = wav.getframerate(), wav.getnframes()
            if not 8000 <= rate <= 192000 or frames == 0:
                raise ValueError('expected nonempty PCM at 8000..192000 Hz')
            raw = wav.readframes(frames)
            if len(raw) != frames * channels * 2:
                raise ValueError('truncated PCM recording')
    except (wave.Error, EOFError) as error:
        raise ValueError('invalid PCM WAV: ' + str(error)) from error
    samples = array('h')
    samples.frombytes(raw)
    if sys.byteorder != 'little':
        samples.byteswap()
    return {
        'sample_rate': rate, 'frames': frames, 'duration_s': frames / rate,
        'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'channels': [samples[channel::channels] for channel in range(channels)],
    }


def bounds(window, recording, name):
    if len(window) != 2:
        raise ValueError(name + ' requires start and end')
    start, end = (number(value, name) for value in window)
    if not 0 <= start < end <= recording['duration_s']:
        raise ValueError(name + ' must lie within the recording')
    # Quantize inward: never inspect samples outside the declared window.
    first = math.ceil(start * recording['sample_rate'])
    last = math.floor(end * recording['sample_rate'])
    if first >= last:
        raise ValueError(name + ' has no complete samples')
    return first, last


def rms(samples):
    return math.sqrt(sum(value * value for value in samples) / len(samples)) / 32768


def detect(recording, channel, window, noise_window, threshold=.02,
           frame_ms=10.0, min_active_ms=20.0):
    """Detect sustained RMS activity, NOT speech, in the recording clock.

    Returned end is exclusive. Resolution is one frame; no backdating from
    transcript/decoded callbacks. Short isolated clicks do not count as speech.
    """
    threshold = number(threshold, 'threshold')
    frame_ms = number(frame_ms, 'frame_ms')
    min_active_ms = number(min_active_ms, 'min_active_ms')
    if not 0 < threshold < 1 or not 0 < frame_ms <= min_active_ms:
        raise ValueError('require 0 < threshold < 1 and 0 < frame_ms <= min_active_ms')
    first, last = bounds(window, recording, 'analysis window')
    quiet_first, quiet_last = bounds(noise_window, recording, 'noise window')
    samples, rate = recording['channels'][channel], recording['sample_rate']
    noise = rms(samples[quiet_first:quiet_last])
    if threshold <= noise * 2:
        raise ValueError('threshold must exceed measured noise RMS by more than 6 dB')
    step = max(1, round(rate * frame_ms / 1000))
    minimum = math.ceil(rate * min_active_ms / 1000)
    if last - first < minimum + 2 * step:
        raise ValueError('analysis window is too short for activity and quiet guards')
    runs, run = [], None
    for begin in range(first, last, step):
        end = min(begin + step, last)
        active = rms(samples[begin:end]) >= threshold
        if active and (begin == first or end == last):
            raise ValueError('activity touches a window boundary; widen it to include quiet guards')
        if active and run is None:
            run = begin
        if not active and run is not None:
            if begin - run >= minimum:
                runs.append((run, begin))
            run = None
    if run is not None and last - run >= minimum:
        runs.append((run, last))
    if runs and (runs[0][0] == first or runs[-1][1] == last):
        raise ValueError('activity touches a window boundary; widen it to include quiet guards')
    return {
        'first_activity_s': runs[0][0] / rate if runs else None,
        'last_activity_end_s': runs[-1][1] / rate if runs else None,
        'activity_regions_s': [[begin / rate, end / rate] for begin, end in runs],
        'noise_rms': noise, 'threshold_rms': threshold,
        'resolution_s': step / rate, 'window_s': list(window),
        'noise_window_s': list(noise_window),
    }


def analyze(path, input_window, output_window, input_noise_window, output_noise_window,
            threshold=.02, frame_ms=10.0, min_active_ms=20.0,
            label='unreviewed', naturalness='unreviewed', calibration=None):
    if label not in {'correct', 'wrong', 'unreviewed'}:
        raise ValueError('invalid correctness label')
    if naturalness not in {'win', 'tie', 'loss', 'unreviewed'}:
        raise ValueError('invalid naturalness label')
    recording = load_pcm(path, 2)
    reference = detect(recording, 0, input_window, input_noise_window,
                       threshold, frame_ms, min_active_ms)
    output = detect(recording, 1, output_window, output_noise_window,
                    threshold, frame_ms, min_active_ms)
    if reference['first_activity_s'] is None:
        raise ValueError('reference window contains no sustained input activity')
    input_end = reference['last_activity_end_s']
    onset, output_end = output['first_activity_s'], output['last_activity_end_s']
    if label == 'correct' and onset is None:
        raise ValueError('silent output cannot be labelled correct')
    checked = calibration is not None
    if checked:
        validate_calibration(calibration, frame_ms, min_active_ms, threshold)
    return {
        'schema': 'voice-latency-analysis-v1', 'capture_valid': True,
        'recording_sha256': recording['sha256'],
        'sample_rate': recording['sample_rate'], 'duration_s': recording['duration_s'],
        'clock': 'single stereo recorder; seconds from first sample',
        'detection': {'reference': reference, 'output': output,
                      'candidate_latency_s': None if onset is None else onset - input_end},
        'evaluation': {
            'label': label, 'naturalness': naturalness,
            'input_last_speech_end_s': input_end if label == 'correct' else None,
            'accepted_output_onset_s': onset if label == 'correct' else None,
            'complete_output_end_s': output_end if label == 'correct' else None,
            'accepted_latency_s': onset - input_end if label == 'correct' else None,
        },
        'synthetic_detector_calibration_checked': checked,
        'limits': [
            'RMS activity is not speech identification; bilingual human labels are required.',
            'Output end is the last activity in its window, not proof of semantic completeness.',
            'Synthetic calibration does not calibrate physical delay, crosstalk or leakage.',
            'No device run, acoustic improvement or quality acceptance is established.',
        ],
    }


def write_fixture(path, offset_s=.4, rate=16000):
    """Write deterministic non-speech sounds; output starts offset after input end."""
    offset_s = number(offset_s, 'offset_s')
    if not -.3 <= offset_s <= 10:
        raise ValueError('calibration offset must be between -.3 and 10 seconds')
    if isinstance(rate, bool) or not isinstance(rate, int) or not 8000 <= rate <= 192000:
        raise ValueError('sample rate must be an integer between 8000 and 192000')
    input_start, input_end = round(.2 * rate), round(.6 * rate)
    output_start = input_end + round(offset_s * rate)
    output_end = output_start + round(.4 * rate)
    frames = max(input_end, output_end) + round(.2 * rate)
    samples = array('h')
    for index in range(frames):
        # A square wave avoids zero-crossing dependence at synthetic boundaries.
        sound = 12000 if (index // max(1, rate // 880)) % 2 else -12000
        samples.append(sound if input_start <= index < input_end else 0)
        samples.append(sound if output_start <= index < output_end else 0)
    if sys.byteorder != 'little':
        samples.byteswap()
    with wave.open(str(path), 'wb') as wav:
        wav.setparams((2, 2, rate, frames, 'NONE', 'not compressed'))
        wav.writeframes(samples.tobytes())
    return {
        'input_end_s': input_end / rate, 'output_onset_s': output_start / rate,
        'output_end_s': output_end / rate, 'offset_s': (output_start - input_end) / rate,
    }


def calibrate(path, offset_s=.4, frame_ms=10.0, min_active_ms=20.0, threshold=.02):
    expected = write_fixture(path, offset_s)
    recording = load_pcm(path, 2)
    window = (0, recording['duration_s'])
    reference = detect(recording, 0, window, (0, .1), threshold, frame_ms, min_active_ms)
    output = detect(recording, 1, window, (0, .1), threshold, frame_ms, min_active_ms)
    if reference['first_activity_s'] is None or output['first_activity_s'] is None:
        raise ValueError('calibration signal is below the detector threshold or duration')
    measured = {
        'input_end_s': reference['last_activity_end_s'],
        'output_onset_s': output['first_activity_s'],
        'output_end_s': output['last_activity_end_s'],
        'offset_s': output['first_activity_s'] - reference['last_activity_end_s'],
    }
    # Each absolute boundary has <= one frame error, differences <= two.
    uncertainty = 2 * reference['resolution_s']
    passed = all(abs(measured[key] - value) <= uncertainty + 1e-9
                 for key, value in expected.items())
    return {
        'schema': 'voice-latency-calibration-v1', 'passed': passed,
        'kind': 'synthetic detector only, not physical capture calibration',
        'recording_sha256': recording['sha256'], 'expected': expected,
        'measured': measured, 'uncertainty_s': uncertainty,
        'settings': {'frame_ms': frame_ms, 'min_active_ms': min_active_ms, 'threshold': threshold},
    }


def validate_calibration(report, frame_ms, min_active_ms, threshold):
    if (report.get('schema') != 'voice-latency-calibration-v1'
            or report.get('passed') is not True
            or report.get('settings') != {'frame_ms': frame_ms,
                                          'min_active_ms': min_active_ms,
                                          'threshold': threshold}):
        raise ValueError('calibration must pass with the same detector settings')
    tolerance = number(report.get('uncertainty_s'), 'calibration uncertainty')
    if not 0 < tolerance <= 2 * frame_ms / 1000 + 1 / 8000:
        raise ValueError('invalid calibration uncertainty')
    for key in ('input_end_s', 'output_onset_s', 'output_end_s', 'offset_s'):
        expected = number(report.get('expected', {}).get(key), 'calibration expectation')
        measured = number(report.get('measured', {}).get(key), 'calibration measurement')
        if abs(expected - measured) > tolerance + 1e-9:
            raise ValueError('calibration measurement exceeds uncertainty')


def percentile(values, fraction):
    if not values:
        return None
    if not 0 < fraction <= 1:
        raise ValueError('percentile must be in (0, 1]')
    return sorted(values)[math.ceil(fraction * len(values)) - 1]


def distribution(values):
    return {'n': len(values), 'p50_s': percentile(values, .5), 'p95_s': percentile(values, .95)}


def identity(trial):
    fixture = trial.get('fixture_id')
    repeat = trial.get('repetition')
    direction = trial.get('direction')
    if (not isinstance(fixture, str) or not fixture.strip()
            or type(repeat) is not int or repeat < 1
            or not isinstance(direction, str) or not direction.strip()
            or trial.get('condition') not in CONDITIONS or trial.get('arm') not in {'A', 'B'}):
        raise ValueError('invalid scheduled trial identity')
    return fixture, repeat, direction, trial['condition'], trial['arm']


def trial_score(trial):
    status = trial.get('status')
    if status not in STATUSES:
        raise ValueError('invalid trial status')
    if status in {'missing', 'invalid'}:
        return None, status
    analysis = trial.get('analysis')
    if (not isinstance(analysis, dict) or analysis.get('schema') != 'voice-latency-analysis-v1'
            or analysis.get('capture_valid') is not True):
        return None, 'invalid'
    if status != 'success':
        return TIMEOUT_SECONDS, status
    evaluation = analysis.get('evaluation', {})
    if not isinstance(evaluation, dict):
        return None, 'invalid'
    latency = evaluation.get('accepted_latency_s')
    if evaluation.get('label') != 'correct' or latency is None:
        return None, 'unreviewed_success'
    try:
        latency = number(latency, 'accepted latency')
        onset = number(evaluation.get('accepted_output_onset_s'), 'accepted onset')
        input_end = number(evaluation.get('input_last_speech_end_s'), 'input end')
        output_end = number(evaluation.get('complete_output_end_s'), 'output end')
    except ValueError:
        return None, 'invalid'
    if output_end < onset or not math.isclose(latency, onset - input_end, abs_tol=1e-9):
        return None, 'invalid'
    # Overlap is meaningful but is not serial Day turn latency. Retain it only
    # in its own category; a negative warmed latency is a protocol violation.
    if latency < 0 and trial['condition'] != 'interruption':
        return None, 'invalid'
    if latency >= TIMEOUT_SECONDS:
        return TIMEOUT_SECONDS, 'timeout'
    return latency, 'success'


def summarize(manifest):
    scheduled, observed = {}, {}
    for item in manifest['scheduled']:
        key = identity(item)
        if key in scheduled:
            raise ValueError('duplicate scheduled identity')
        scheduled[key] = item
    if not scheduled:
        raise ValueError('schedule must not be empty')
    for item in manifest['trials']:
        key = identity(item)
        if key not in scheduled or key in observed:
            raise ValueError('unscheduled or duplicate trial')
        observed[key] = item
    rows, groups, blocks = [], defaultdict(list), defaultdict(dict)
    for key, item in scheduled.items():
        trial = observed.get(key, {**item, 'status': 'missing'})
        score, effective = trial_score(trial)
        row = {field: item[field] for field in ('fixture_id', 'repetition', 'direction', 'condition', 'arm')}
        row.update({'reported_status': trial['status'], 'effective_status': effective,
                    'qualification_score_s': score, 'analysis': trial.get('analysis')})
        if trial.get('analysis_error'):
            row['analysis_error'] = trial['analysis_error']
        rows.append(row)
        groups[key[2:]].append(row)
        blocks[key[:4]][key[4]] = score
    summaries = []
    for (direction, condition, arm), group in sorted(groups.items()):
        scores = [row['qualification_score_s'] for row in group if row['qualification_score_s'] is not None]
        successful = [row['qualification_score_s'] for row in group if row['effective_status'] == 'success']
        counts = dict(Counter(row['effective_status'] for row in group))
        missing = len(scores) != len(group)
        calibrated = all(isinstance(row['analysis'], dict)
                         and row['analysis'].get('synthetic_detector_calibration_checked') is True
                         for row in group)
        all_trials = distribution(scores) if not missing else {'n': len(group), 'p50_s': None, 'p95_s': None}
        # This is only a descriptive warmed-Day latency threshold, not product acceptance.
        gate = 'blocked' if missing or not calibrated or condition != 'warmed' else (
            'met' if all_trials['p50_s'] < 1.2 and all_trials['p95_s'] < 2 else 'not_met')
        summaries.append({
            'direction': direction, 'condition': condition, 'arm': arm,
            'scheduled_n': len(group), 'status_counts': counts,
            'failure_rate': sum(counts.get(status, 0) for status in STATUSES - {'success', 'missing', 'invalid'}) / len(group),
            'missing_or_invalid_n': len(group) - len(scores),
            'all_scheduled_qualification': all_trials,
            'observed_scores_diagnostic': distribution(scores),
            'successful_only_conditional': distribution(successful),
            'warmed_day_latency_threshold': gate,
        })
    paired = []
    for key, arms in blocks.items():
        paired.append({'fixture_id': key[0], 'repetition': key[1], 'direction': key[2],
                       'condition': key[3], 'A_score_s': arms.get('A'), 'B_score_s': arms.get('B')})
    return {
        'schema': 'voice-latency-summary-v1', 'timeout_penalty_s': TIMEOUT_SECONDS,
        'trials': rows, 'groups': summaries, 'paired_blocks': paired,
        'paired_data_complete': all(row['A_score_s'] is not None and row['B_score_s'] is not None for row in paired),
        'qualification': 'blocked: descriptive only; physical calibration, held-out corpus, repeated trials, both directions, quality/entity/echo/interruption gates and fresh review require external evidence',
        'comparison': 'not evaluated; paired-bootstrap rule is derived and must be confirmed before experiments',
        'score_note': '15s is a censored failure score, not an acoustic timestamp; missing/invalid captures are not scored failures',
    }


def read_json(path):
    return json.loads(Path(path).read_text())


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    calibration = commands.add_parser('calibrate', help='generate and check a known-offset stereo fixture')
    calibration.add_argument('wav')
    calibration.add_argument('--offset', type=float, default=.4, help='output onset minus input end, seconds')
    calibration.add_argument('--report', help='also save calibration JSON here')
    source = commands.add_parser('source', help='validate a fixed synthetic mono PCM16 playback source')
    source.add_argument('wav')
    source.add_argument('--emit', help='copy validated PCM source here; no playback is attempted')
    analysis = commands.add_parser('analyze', help='detect activity in explicit windows on one stereo clock')
    analysis.add_argument('wav')
    for name in ('input-window', 'output-window', 'input-noise-window', 'output-noise-window'):
        analysis.add_argument('--' + name, nargs=2, type=float, required=True, metavar=('START', 'END'))
    analysis.add_argument('--label', choices=('correct', 'wrong', 'unreviewed'), default='unreviewed')
    analysis.add_argument('--naturalness', choices=('win', 'tie', 'loss', 'unreviewed'), default='unreviewed')
    analysis.add_argument('--calibration', help='passing synthetic calibration JSON with matching settings')
    for command in (calibration, analysis):
        command.add_argument('--threshold', type=float, default=.02, help='normalized RMS, must exceed measured noise by >6 dB')
        command.add_argument('--frame-ms', type=float, default=10)
        command.add_argument('--min-active-ms', type=float, default=20)
    summary = commands.add_parser('summarize', help='retain every scheduled trial and both paired arms')
    summary.add_argument('manifest')
    args = parser.parse_args(argv)
    try:
        if args.command == 'calibrate':
            result = calibrate(args.wav, args.offset, args.frame_ms, args.min_active_ms, args.threshold)
            if args.report:
                Path(args.report).write_text(json.dumps(result, indent=2) + '\n')
        elif args.command == 'source':
            recording = load_pcm(args.wav, 1)
            peak = max(abs(value) for value in recording['channels'][0]) / 32768
            if peak < .02 or rms(recording['channels'][0]) < .001:
                raise ValueError('source is silent or below usable signal level')
            result = {key: recording[key] for key in ('sha256', 'sample_rate', 'frames', 'duration_s')}
            result.update({'peak': peak, 'speech_validated': False, 'playback': 'not attempted; external common-clock stereo capture required'})
            if args.emit:
                Path(args.emit).write_bytes(Path(args.wav).read_bytes())
        elif args.command == 'analyze':
            result = analyze(args.wav, args.input_window, args.output_window,
                             args.input_noise_window, args.output_noise_window,
                             args.threshold, args.frame_ms, args.min_active_ms,
                             args.label, args.naturalness,
                             read_json(args.calibration) if args.calibration else None)
        else:
            manifest = read_json(args.manifest)
            for trial in manifest['trials']:
                if isinstance(trial.get('analysis'), str):
                    analysis_path = Path(args.manifest).parent / trial['analysis']
                    try:
                        trial['analysis'] = read_json(analysis_path)
                    except (OSError, ValueError) as error:
                        trial['analysis_error'] = str(error)
                        trial['analysis'] = None
            result = summarize(manifest)
        print(json.dumps(result, indent=2, allow_nan=False))
        return 1 if args.command == 'calibrate' and not result['passed'] else 0
    except (OSError, ValueError, KeyError, TypeError, OverflowError) as error:
        parser.error(str(error))


if __name__ == '__main__':
    sys.exit(main())
