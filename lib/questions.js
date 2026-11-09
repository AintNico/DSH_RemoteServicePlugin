import { randomUUID } from 'node:crypto'

/**
 * Holds one pending `user-questions/request` and drives the phone-facing state machine.
 *
 * The request is *parked*, not claimed forever: the phone has `timeoutMs` to answer, and
 * when it does not, `next()` runs the remaining answerers (the DSH web UI), so a question
 * asked while somebody sits at the computer still shows up there.
 */
export function createQuestionBroker({ broadcast, timeoutMs = 120_000 }) {
  const pending = new Map()

  function frame(entry, extra = {}) {
    return {
      kind: 'question',
      id: entry.id,
      sessionId: entry.sessionId,
      questions: entry.questions,
      expiresAt: entry.expiresAt,
      ...extra,
    }
  }

  function settle(id, answer, mode) {
    const entry = pending.get(id)
    if (!entry || entry.settled) return false

    entry.settled = true
    clearTimeout(entry.timer)
    pending.delete(id)
    broadcast({ kind: 'question-resolved', id, outcome: mode === 'next' ? 'delegated' : 'answered' })

    if (mode === 'next') {
      Promise.resolve()
        .then(() => entry.next())
        .then(entry.resolve, entry.reject)
    } else {
      entry.resolve(answer)
    }
    return true
  }

  /** Register one request; resolves with the answer that ends the waterfall. */
  function request(req, next) {
    const questions = normalizeQuestions(req?.questions)
    if (!questions.length) {
      // Nothing actionable (e.g. an empty plan review): never hold the pipeline open.
      return Promise.resolve().then(() => next())
    }

    const id = randomUUID()
    const expiresAt = Date.now() + timeoutMs

    return new Promise((resolve, reject) => {
      const entry = {
        id,
        sessionId: req?.agent?.id ?? null,
        questions,
        expiresAt,
        next,
        resolve,
        reject,
        settled: false,
        timer: null,
      }
      pending.set(id, entry)
      broadcast(frame(entry))

      entry.timer = setTimeout(() => settle(id, null, 'next'), timeoutMs)
      req?.signal?.addEventListener('abort', () => settle(id, null, 'next'), { once: true })
    })
  }

  /** Called by a client. Returns false when the answer is unknown or already settled. */
  function answer(id, answers) {
    const entry = pending.get(id)
    if (!entry) return false
    const cleaned = sanitizeAnswers(answers, entry.questions)
    if (!cleaned) return false
    return settle(id, { answers: cleaned }, 'value')
  }

  function list() {
    return [...pending.values()].map((entry) => frame(entry))
  }

  function dispose() {
    for (const id of [...pending.keys()]) settle(id, null, 'next')
    pending.clear()
  }

  return { request, answer, list, dispose }
}

/**
 * The answerer registered on `user-questions/request`. `prepend` sits ahead of the DSH web
 * answerer so the phone sees the question first; on timeout we hand it back.
 */
export function installQuestionObserver(ctx, broker) {
  const dispose = ctx.on(
    'user-questions/request',
    (request, next) => broker.request(request, next),
    { prepend: true },
  )
  return { dispose: () => dispose?.() }
}

/** Flat, JSON-safe shape the clients render; unknown fields are dropped on purpose. */
function normalizeQuestions(input) {
  if (!Array.isArray(input)) return []
  const questions = []

  for (const item of input) {
    if (!item || typeof item !== 'object') continue
    const options = Array.isArray(item.options)
      ? item.options
          .filter((option) => option && typeof option.label === 'string' && option.label.trim())
          .map((option) => ({
            label: option.label,
            description: typeof option.description === 'string' ? option.description : undefined,
          }))
      : []

    // A plan review carries the approval wording in `intent` instead of an option list.
    if (!options.length && item.intent?.kind === 'plan-review') {
      const approve = typeof item.intent.approve === 'string' && item.intent.approve.trim()
        ? item.intent.approve
        : '批准'
      options.push({ label: approve, description: undefined })
    }

    questions.push({
      id: String(item.id ?? questions.length),
      question: String(item.question ?? ''),
      detail: typeof item.detail === 'string' ? item.detail : undefined,
      header: typeof item.header === 'string' ? item.header : undefined,
      options,
      multiSelect: Boolean(item.multiSelect),
    })
  }

  return questions.filter((question) => question.question || question.options.length)
}

/**
 * Accepts `[{ id, selected: string[], custom?: string }]` and keeps only selections that
 * belong to the parked question set.
 */
function sanitizeAnswers(answers, questions) {
  if (!Array.isArray(answers) || !answers.length) return null

  const byId = new Map(questions.map((question) => [question.id, question]))
  const cleaned = []

  for (const answer of answers) {
    if (!answer || typeof answer !== 'object') continue
    const question = byId.get(String(answer.id ?? ''))
    if (!question) continue

    const allowed = new Set(question.options.map((option) => option.label))
    const selected = Array.isArray(answer.selected)
      ? answer.selected.map(String).filter((label) => allowed.has(label))
      : []
    const custom = typeof answer.custom === 'string' && answer.custom.trim()
      ? answer.custom.trim().slice(0, 2000)
      : undefined

    if (!selected.length && !custom) continue
    cleaned.push({ id: question.id, selected, custom })
  }

  return cleaned.length ? cleaned : null
}
