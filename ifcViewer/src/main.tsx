import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
//import { DemoPage } from './DemoPage'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
    {/* <DemoPage /> */}
  </StrictMode>,
)