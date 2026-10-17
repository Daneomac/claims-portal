import { setupServer } from 'msw/node'

// Each test registers the handlers it needs with server.use(...)
export const server = setupServer()
