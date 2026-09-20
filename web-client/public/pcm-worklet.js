/**
 * 마이크 입력을 16bit PCM 으로 바꿔 메인 스레드로 넘기는 AudioWorklet.
 * (SPEC §2.1-2: 16kHz 모노 16bit LE)
 *
 * AudioContext 를 16kHz 로 만들기 때문에 여기서는 리샘플링하지 않는다.
 */
class PcmRecorderProcessor extends AudioWorkletProcessor {
  process(inputs) {
    const channel = inputs[0]?.[0];
    if (!channel) {
      return true;
    }
    const pcm = new Int16Array(channel.length);
    for (let i = 0; i < channel.length; i++) {
      const sample = Math.max(-1, Math.min(1, channel[i]));
      pcm[i] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
    }
    this.port.postMessage(pcm, [pcm.buffer]);
    return true;
  }
}

registerProcessor("pcm-recorder", PcmRecorderProcessor);
