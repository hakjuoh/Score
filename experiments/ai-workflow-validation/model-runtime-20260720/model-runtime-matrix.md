# AI model / reasoning / runtime smoke matrix

- Run: `model-runtime-20260720`
- Base URL: `http://127.0.0.1:9000`
- Cases: 50; passed: 50; failed: 0
- Pass rate: 100.0%

| Model | Reasoning | Runtime | Result | ms | Prompt tokens | Completion tokens | Error |
|---|---|---|---:|---:|---:|---:|---|
| claude-fable-5 | low | default | PASS | 14065 | 3 | 187 |  |
| claude-fable-5 | low | claude | PASS | 13088 | 3 | 175 |  |
| claude-fable-5 | medium | default | PASS | 13451 | 3 | 182 |  |
| claude-fable-5 | medium | claude | PASS | 14781 | 3 | 183 |  |
| claude-fable-5 | high | default | PASS | 14436 | 3 | 220 |  |
| claude-fable-5 | high | claude | PASS | 15874 | 3 | 211 |  |
| claude-fable-5 | max | default | PASS | 17908 | 3 | 653 |  |
| claude-fable-5 | max | claude | PASS | 21748 | 3 | 910 |  |
| claude-opus-4_8 | low | default | PASS | 6750 | 3 | 174 |  |
| claude-opus-4_8 | low | claude | PASS | 12101 | 3 | 174 |  |
| claude-opus-4_8 | medium | default | PASS | 6939 | 3 | 163 |  |
| claude-opus-4_8 | medium | claude | PASS | 11221 | 3 | 163 |  |
| claude-opus-4_8 | high | default | PASS | 6676 | 3 | 244 |  |
| claude-opus-4_8 | high | claude | PASS | 7324 | 3 | 241 |  |
| claude-opus-4_8 | max | default | PASS | 15540 | 3 | 768 |  |
| claude-opus-4_8 | max | claude | PASS | 13216 | 3 | 701 |  |
| claude-sonnet-5 | low | default | PASS | 6346 | 3 | 172 |  |
| claude-sonnet-5 | low | claude | PASS | 5946 | 3 | 172 |  |
| claude-sonnet-5 | medium | default | PASS | 9807 | 3 | 172 |  |
| claude-sonnet-5 | medium | claude | PASS | 9314 | 3 | 172 |  |
| claude-sonnet-5 | high | default | PASS | 6449 | 3 | 232 |  |
| claude-sonnet-5 | high | claude | PASS | 6604 | 3 | 174 |  |
| claude-sonnet-5 | max | default | PASS | 28446 | 3 | 2605 |  |
| claude-sonnet-5 | max | claude | PASS | 52908 | 3 | 4744 |  |
| claude-haiku-4_5 | default | default | PASS | 4021 | 4436 | 174 |  |
| claude-haiku-4_5 | default | claude | PASS | 4724 | 4436 | 240 |  |
| gpt-5_6-sol | low | default | PASS | 6608 | 3982 | 163 |  |
| gpt-5_6-sol | low | openai | PASS | 8654 | 3982 | 178 |  |
| gpt-5_6-sol | medium | default | PASS | 6020 | 3982 | 160 |  |
| gpt-5_6-sol | medium | openai | PASS | 15853 | 3986 | 153 |  |
| gpt-5_6-sol | high | default | PASS | 7575 | 3982 | 186 |  |
| gpt-5_6-sol | high | openai | PASS | 5767 | 3982 | 205 |  |
| gpt-5_6-sol | xhigh | default | PASS | 8817 | 3986 | 183 |  |
| gpt-5_6-sol | xhigh | openai | PASS | 6239 | 3982 | 229 |  |
| gpt-5_6-terra | low | default | PASS | 5165 | 3982 | 138 |  |
| gpt-5_6-terra | low | openai | PASS | 4377 | 3982 | 139 |  |
| gpt-5_6-terra | medium | default | PASS | 5948 | 3982 | 179 |  |
| gpt-5_6-terra | medium | openai | PASS | 5929 | 3982 | 166 |  |
| gpt-5_6-terra | high | default | PASS | 6151 | 3982 | 262 |  |
| gpt-5_6-terra | high | openai | PASS | 5587 | 3982 | 179 |  |
| gpt-5_6-terra | xhigh | default | PASS | 6492 | 3986 | 340 |  |
| gpt-5_6-terra | xhigh | openai | PASS | 5752 | 3986 | 206 |  |
| gpt-5_6-luna | low | default | PASS | 5279 | 3986 | 181 |  |
| gpt-5_6-luna | low | openai | PASS | 6060 | 3986 | 178 |  |
| gpt-5_6-luna | medium | default | PASS | 5395 | 3986 | 247 |  |
| gpt-5_6-luna | medium | openai | PASS | 4650 | 3986 | 201 |  |
| gpt-5_6-luna | high | default | PASS | 5560 | 3982 | 310 |  |
| gpt-5_6-luna | high | openai | PASS | 5866 | 3986 | 278 |  |
| gpt-5_6-luna | xhigh | default | PASS | 7027 | 3984 | 464 |  |
| gpt-5_6-luna | xhigh | openai | PASS | 6957 | 3982 | 427 |  |
