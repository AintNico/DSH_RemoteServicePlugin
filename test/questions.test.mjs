import test from 'node:test'
import assert from 'node:assert/strict'
import { createQuestionBroker, installQuestionObserver } from '../lib/questions.js'

/** Collects every frame the broker broadcasts so a test can assert on them. */
function harness({ timeoutMs = 5000 } = {}) {
  const frames = []
  const broker = createQuestionBroker({
    broadcast: (payload) => frames.push(payload),
    timeoutMs,
  })
  return { broker, frames }
}

const APPROVE = '批准'

test('a plan-review without explicit options becomes one 批准 choice', async () => {
  const { broker, frames } = harness()
  const answer = { answers: [] }

  const pending = broker.request(
    {
      agent: { id: 'session-1' },
      questions: [
        {
          id: 'plan',
          header: '方案审批',
          question: '是否批准这个方案？',
          intent: { kind: 'plan-review', approve: APPROVE },
        },
      ],
    },
    async () => answer,
  )

  const frame = frames.at(-1)
  assert.equal(frame.kind, 'question')
  assert.equal(frame.questions[0].options.length, 1, 'exactly one synthesized option')
  assert.equal(frame.questions[0].options[0].label, APPROVE)

  assert.equal(broker.answer(frame.id, [{ id: 'plan', selected: [APPROVE] }]), true)
  assert.deepEqual(await pending, { answers: [{ id: 'plan', selected: [APPROVE] }] })
})

test('a plan-review keeps explicit options instead of synthesizing 批准', () => {
  const { broker, frames } = harness()
  broker.request(
    {
      questions: [
        {
          id: 'plan',
          question: '选方案',
          options: [{ label: 'A' }, { label: 'B' }],
          intent: { kind: 'plan-review', approve: APPROVE },
        },
      ],
    },
    async () => ({ answers: [] }),
  )

  const options = frames.at(-1).questions[0].options
  assert.deepEqual(options.map((option) => option.label), ['A', 'B'])
})

test('an empty question list never parks the waterfall', async () => {
  const { broker, frames } = harness()
  let delegated = 0

  const result = await broker.request({ questions: [] }, async () => {
    delegated += 1
    return { answers: [] }
  })

  assert.equal(delegated, 1)
  assert.equal(frames.length, 0, 'nothing is broadcast when there is nothing to ask')
  assert.deepEqual(result, { answers: [] })
})

test('answers for unknown questions or options are refused', () => {
  const { broker, frames } = harness()
  broker.request(
    { questions: [{ id: 'plan', question: '选哪个？', options: [{ label: 'A' }] }] },
    async () => ({ answers: [] }),
  )
  const { id } = frames.at(-1)

  assert.equal(broker.answer(id, [{ id: 'nope', selected: ['A'] }]), false, 'unknown question id')
  assert.equal(broker.answer(id, [{ id: 'plan', selected: ['B'] }]), false, 'unknown option')
  assert.equal(broker.answer(id, []), false, 'no answers at all')
  assert.equal(broker.list().length, 1, 'the request is still pending')
})

test('a hand-written answer is accepted without a selection', async () => {
  const { broker, frames } = harness()
  const pending = broker.request(
    { questions: [{ id: 'plan', question: '选哪个？', options: [{ label: 'A' }] }] },
    async () => ({ answers: [] }),
  )

  assert.equal(broker.answer(frames.at(-1).id, [{ id: 'plan', selected: [], custom: '  都要  ' }]), true)
  assert.deepEqual(await pending, { answers: [{ id: 'plan', selected: [], custom: '都要' }] })
})

test('multi-select keeps only the offered labels', async () => {
  const { broker, frames } = harness()
  const pending = broker.request(
    {
      questions: [
        {
          id: 'plan',
          question: '要改哪些？',
          multiSelect: true,
          options: [{ label: '文档' }, { label: '代码' }],
        },
      ],
    },
    async () => ({ answers: [] }),
  )

  broker.answer(frames.at(-1).id, [{ id: 'plan', selected: ['文档', '乱写'] }])
  assert.deepEqual(await pending, { answers: [{ id: 'plan', selected: ['文档'] }] })
})

test('an unanswered question hands itself back to the next answerer', async () => {
  const { broker, frames } = harness({ timeoutMs: 30 })
  let delegated = 0

  const result = await broker.request(
    { questions: [{ id: 'plan', question: '选哪个？', options: [{ label: 'A' }] }] },
    async () => {
      delegated += 1
      return 'delegated-outcome'
    },
  )

  assert.equal(delegated, 1, 'next() runs when the phone stays silent')
  assert.equal(result, 'delegated-outcome')
  assert.equal(frames.at(-1).kind, 'question-resolved')
  assert.equal(frames.at(-1).outcome, 'delegated')
  assert.equal(broker.list().length, 0)
})

test('an aborted request is handed back immediately', async () => {
  const { broker, frames } = harness({ timeoutMs: 10_000 })
  const controller = new AbortController()
  let delegated = 0

  const pending = broker.request(
    {
      questions: [{ id: 'plan', question: '选哪个？', options: [{ label: 'A' }] }],
      signal: controller.signal,
    },
    async () => {
      delegated += 1
      return { answers: [] }
    },
  )

  controller.abort()
  await pending
  assert.equal(delegated, 1)
  assert.equal(broker.list().length, 0)
})

test('the observer registers a prepended user-questions handler', () => {
  const { broker } = harness()
  const registered = []
  const fakeCtx = {
    on: (name, handler, options) => {
      registered.push({ name, handler, options })
      return () => {}
    },
  }

  const observer = installQuestionObserver(fakeCtx, broker)
  assert.equal(registered.length, 1)
  assert.equal(registered[0].name, 'user-questions/request')
  assert.equal(registered[0].options.prepend, true)
  assert.equal(typeof registered[0].handler, 'function')
  observer.dispose()
})
