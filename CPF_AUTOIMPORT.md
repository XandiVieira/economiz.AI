# Feature: Auto-import de notas por CPF ("CPF na nota" → aparece sozinho no app)

**Status:** SPIKE feito (2026-07-07). Viável **condicionalmente** — NÃO com só o CPF.
**Origem:** ideia de tester — em vez de escanear o QR, o usuário pede "CPF na nota"
no caixa e as compras apareceriam automaticamente no app.

---

## A ideia

Hoje o fluxo é: usuário escaneia o QR da NFC-e → a gente busca/parseia. A proposta é
eliminar o scan: como toda NFC-e emitida com CPF fica registrada na SEFAZ, bastaria
"puxar por CPF" e importar tudo sozinho. Desde **nov/2025 a NFC-e é exclusiva para
CPF** (vendas a CNPJ viraram NF-e modelo 55), então o dado por-CPF é ainda mais central.

## Como o dado realmente é acessível

Vários estados têm **"Consulta NFC-e por CPF"**: o consumidor faz um cadastro no portal
da SEFAZ, recebe uma senha por e-mail e loga com **CPF + senha** (ou **certificado
digital / e-CPF**). Aí vê a **lista completa de todas as notas emitidas no nome dele**.
O dado existe e é puxável por CPF — mas **sempre atrás de login do próprio titular**.

## Resultado da spike (2026-07-07)

Testado contra a API do Infosimples (que já integramos) e o catálogo SEFAZ:

- O endpoint **NFC-e do Infosimples é só POR CHAVE** (param `nfce` = os 44 dígitos).
  **Não existe** "manda CPF → recebe a lista de notas".
- Chamada da NFC-e unificada com `cpf=01216437009` (sem chave) → `code 600` (erro).
- A listagem "Notas Recebidas" (ex.: Prefeitura SP) exige **`pkcs12_cert` +
  `pkcs12_pass`** — ou seja, **certificado digital (e-CPF A1)** do titular.
- Portais estaduais de "consulta por CPF" (ex.: AM) exigem **cadastro + senha** do CPF.

**Conclusão dura:** com **apenas o CPF, não se recupera nada** — seria um buraco de
privacidade que (corretamente) não existe. Todo caminho exige a **credencial autenticada
do titular**: senha do portal estadual OU certificado e-CPF. Isso é o mesmo muro gov.br
que já batemos no RS, agora como mecanismo *primário*.

## Desenhos viáveis (todos opt-in, com consentimento LGPD)

- **Path A — senha do portal estadual:** o usuário cadastra o CPF no portal da SEFAZ do
  estado dele e nos dá CPF + senha; a gente (ou o Infosimples, se construírem o endpoint
  "por CPF") faz **polling periódico** (ex.: 1×/dia) e importa as notas novas.
  - Prós: sem scan. Contras: **fragmentado por estado** (cada SEFAZ é diferente; alguns
    atrás de gov.br com MFA/captcha), guardar senha de terceiro é sensível.
- **Path B — certificado e-CPF (A1 pkcs12):** usuário sobe o certificado; autenticamos e
  listamos as notas. Tecnicamente o mais limpo, mas **poucos consumidores têm e-CPF**
  (custa ~R$100-200/ano, é coisa de PJ/contador).

**Comum aos dois:** é **polling, não push** (a SEFAZ não notifica apps de terceiros) →
"aparece em até ~1 dia", não em tempo real. E **LGPD**: guardar credencial/certificado
do usuário é o maior risco do desenho — precisa de cofre de segredos + consentimento
explícito + escopo mínimo.

## Custo

- Via Infosimples: pago por consulta (~R$0,28 hoje na NFC-e por-chave). Um endpoint
  "por CPF" (se existir) seria pago por consulta também → polling diário × usuários = custo
  recorrente a dimensionar.

## Recomendação

- **Não substitui o scan** — mantém o QR como padrão universal (funciona em todo estado,
  sem credencial). O auto-import por CPF vira um **upgrade opt-in** onde for viável.
- **Começar por 1 estado** com portal CPF+senha simples (não-gov.br) OU exigir e-CPF, e
  medir custo/confiabilidade num piloto.

## Próximo passo para continuar a spike

O spike travou por falta de credencial — **um CPF sozinho não basta**. Para prosseguir eu
preciso de **um** destes, para um CPF de teste:
1. **Senha do portal "consulta por CPF"** da SEFAZ de um estado (o titular cadastra e
   passa CPF + senha), ou
2. Um **certificado digital e-CPF A1** (arquivo `.pfx`/`.p12` + senha).

Com isso eu testo a listagem real por CPF de ponta a ponta e digo se dá pra construir.

## Sources

- SEFAZ/AM — NFC-e pode ser consultada pelo CPF: https://www.sefaz.am.gov.br/noticias/9164
- Infosimples — SEFAZ/NFC-e (só por chave): https://infosimples.com/consultas/sefaz-nfce/
- Infosimples — Notas Recebidas SP exige certificado (issue #583):
  https://github.com/infosimples/infosimples/issues/583
- Gov.br/ITI — consultar NF-e online: https://www.gov.br/iti/pt-br/assuntos/noticias/iti-na-midia/como-consultar-nota-fiscal-eletronica-online
