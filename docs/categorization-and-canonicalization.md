# Categorização, Produtização & Marca — estado atual e trade-offs

> Documento de discussão (produto + FE). Descreve como o pipeline funciona hoje,
> por que cada etapa é difícil, a solução atual e suas limitações. Base para
> decidir a experiência de categorização na tela de confirmação.
>
> Última atualização: 2026-07-03.

## O objetivo de fundo

Cada item de nota fiscal precisa virar duas coisas:

1. **Um produto canônico** (compartilhado entre todos os usuários) — é o que
   permite comparar preços entre mercados e casas. Sem isso não existe índice
   colaborativo.
2. **Uma categoria** — alimenta os dashboards de gasto ("você gastou R$X em
   Mercearia").

O texto do cupom é sujo, abreviado e varia por mercado (`ARROZ TIO J 5KG`,
`A.GADO ALCATRA C/OSSO kg`, `SAB HIDR NIVEA ORQUIDEAS 85GR`). Toda a
dificuldade vem daí.

As três etapas difíceis, em ordem: **(1) ligar item → produto canônico**,
**(2) categorizar o produto**, **(3) determinar a marca** (não essencial).

---

## Problema 1 — Ligar item → produto canônico (o mais difícil)

**O que é:** decidir que `ARROZ TIO J 5KG` (uma nota) e `ARROZ TIO JOAO T1 5KG`
(outra) são **o mesmo produto**, e ligar os dois à mesma linha na tabela global
de produtos.

**Por que é difícil:** não existe identificador confiável. O EAN (código de
barras) resolveria, mas **a maioria das notas não traz EAN** nos itens
(hortifruti, açougue, padaria — tudo vendido por kg). Sobra o texto, ambíguo.

**Solução atual (roda na confirmação)** — cascata em ordem de confiança:

1. Já está ligado → pronto
2. **Tem EAN** → match exato por EAN → senão "dedup por metadados" (mesmo nome
   genérico + marca + tamanho) → senão cria produto novo
3. **Sem EAN** → apelido exato (texto normalizado) → **match fuzzy**
   (Jaro-Winkler ≥ 0,85, ex: "tio j" ≈ "tio joao") → se nada bater, fica
   **UNMATCHED**

**Limitação:** o item UNMATCHED **não entra no índice de preços** — fica órfão
esperando. O fuzzy é um equilíbrio delicado: threshold alto perde matches
óbvios, baixo junta produtos diferentes (arroz branco vs parboilizado). Hoje
**não medimos a taxa de UNMATCHED** — é o KPI mais importante que está no
escuro.

---

## Problema 2 — Categorizar um produto

**O que é:** dizer que `SAB HIDR NIVEA` é Higiene Pessoal, `KITKAT` é Mercearia.
9 categorias: Mercearia, Bebidas, Hortifruti, Carnes & Laticínios, Padaria,
Limpeza, Higiene Pessoal, Saúde, Outros.

**Por que é difícil:** texto sujo + colisões de palavra (batata *palito
congelada* é Mercearia, mas "batata" puxa Hortifruti; detergente de *limão*
não é Hortifruti).

**Solução atual** — cascata quando um produto novo é criado:

1. **Dicionário curado** (tabela no banco, ~700 entradas, editável em runtime)
2. **Dicionário aprendido** — cresce sozinho via consenso/ML
3. **ML** (Naive Bayes) — hoje em *shadow* (mede, não aplica)
4. **Catálogo EAN** (Open Food Facts) — sobrepõe o dicionário quando há código
5. **Fallback de mercado** (farmácia → Saúde)
6. Nada bateu → sem categoria

**Ponto crucial:** a categoria mora **no produto**, não no item. Ao comprar
`ARROZ TIO J` de novo, o item liga ao produto existente e **herda a categoria
dele** — o dicionário nem é consultado. Isso faz o sistema melhorar
coletivamente, mas é a origem da complexidade "customização vs canônico".

**Estado real medido (2026-07-03):** golden set de 452 descrições reais →
**99,3% de acurácia** após corrigir o dicionário. A categorização em si está
boa; o problema de "nada categorizado" que a FE viu era a fase de preview (ver
"As duas fases").

---

## Problema 3 — Determinar a marca (não essencial)

**O que é:** extrair "Tio João" de `ARROZ TIO J 5KG`.

**Solução atual:** registro de marcas (tabela, ~400 entradas) + varredura por
frase. Também é possível derivar marcas do catálogo EAN (endpoint
`POST /categorizer/brands/derive-from-catalog`).

**Por que é secundário:** marca serve pra dedup de produto e exibição bonita,
mas nada quebra sem ela. As abreviações de cupom ("TIO J") só vêm de notas
reais — nenhuma fonte externa tem. Candidato a deixar o aprendizado
colaborativo resolver com o tempo.

---

## A tensão central: canônico × customização

Três **camadas** sobre cada item:

| Camada | Escopo | O que é |
|---|---|---|
| **Produto canônico** | Global (todos) | Categoria "oficial" do produto, compartilhada |
| **Override da casa** | Por household | "Nós corrigimos: pra nós isso é X" |
| **Snapshot da confirmação** | Por item | Foto da categoria no momento que confirmou |

**Precedência (implementada 2026-07-03):**

```
override da casa  >  snapshot da confirmação  >  categoria viva do produto
```

**Princípio:** conhecimento novo só afeta entradas novas.

- Usuário corrigiu → sua escolha vale pra sempre (passado e futuro)
- Confirmou sem mexer → histórico congela; a **próxima** compra vem com o
  conhecimento novo
- Consenso (2+ casas corrigindo igual) → melhora o produto pra compras futuras,
  sem reescrever histórico de ninguém

---

## As duas fases (onde a UX vive)

**Fase 1 — Revisão (tela de confirmar):** mostramos nosso **melhor palpite** de
categoria, calculado na hora, sem gravar nada. Todo item vem com
`categorySuggested: true` → o FE renderiza como sugestão (chip tracejado). O
usuário pode aceitar/corrigir. **Nesta fase o item ainda não tem produto
canônico** — a categoria é palpite por texto, não a verdade final do matching.

**Fase 2 — Confirmação:** o trabalho real — liga produtos, cria os que faltam,
congela o snapshot, alimenta o índice de preços. Aqui roda o fuzzy matching
(pesado demais pra rodar a cada abertura da tela).

---

## Como isto conversa com a ideia de UX ("selecione os itens da categoria X")

A proposta de categorização em lote reversa **resolve categoria, mas não toca no
Problema 1 (matching)**, que é o mais difícil.

**A favor:**

- Reduz fricção quando o palpite erra em vários itens
- Garante 100% de itens categorizados antes de confirmar
- Cada seleção vira **override explícito da casa** (camada mais forte) →
  melhora a experiência daquela casa pra sempre, e alimenta o consenso global

**Atenção ao desenhar:**

1. **"Categoria do item" ≠ "produto canônico".** A seleção categoriza a
   *exibição pra aquela casa* (override); não resolve o matching. Casas
   diferentes podem categorizar o mesmo produto diferente — e tudo bem.
2. **Fricção reversa:** "selecione todos de Mercearia" com 40 itens × 9
   categorias pode dar 9 telas. Alternativa: pré-preencher com o palpite (99%
   de acerto) e o usuário só *move* os poucos errados.
3. **Toda categorização manual é ouro:** vira override; 2+ casas concordando
   promove pro produto global. Desenhar pra capturar bem.
4. **Item UNMATCHED** pode receber categoria via override, mas continua fora do
   índice de preços. Decisão de produto: mostrar diferente? pedir ajuda?

**Sugestão de direção:** como a categorização automática já está ~99%, o maior
ganho talvez não seja "categorizar do zero em lote", e sim **"confirme ou
corrija nosso palpite"** — mostrar tudo já categorizado (chip tracejado =
palpite) e o gesto ser só arrastar/corrigir os errados. Mais leve, ainda captura
correção como override. O backend suporta os dois caminhos.

---

## Decisões de produto em aberto

1. **Medir UNMATCHED** — quantos itens ficam órfãos? KPI que falta.
2. **Consenso deve sobrescrever a categoria viva do produto, ou só valer pra
   frente?** Hoje sobrescreve (afeta futuras compras de todos). Alternativa
   conservadora existe mas fragmenta o índice.
3. **Avisar o usuário quando algo que ele comprou mudar de categoria**
   ("opção D") — transparência sem travar o aprendizado.
4. **A tela nova categoriza do zero ou corrige palpite?** (ver acima)
5. **A comunidade converge com o tempo?** O consenso resolve categoria e
   promove ao produto global. Para produto (matching) e marca, o mecanismo de
   convergência automática ainda é parcial — ver "Convergência da comunidade".

---

## Convergência da comunidade (as inconsistências se resolvem sozinhas?)

Meta: com escala, a comunidade deve fechar todas as inconsistências —
**produtos** (matching), **categorias** e **marcas**. Onde estamos:

- **Categoria:** ✅ mecanismo existe. Correções viram override; 2+ casas
  concordando graduam o produto global (source CONSENSUS) e alimentam o
  dicionário aprendido (afeta produtos futuros).
- **Produto (matching):** ⚠️ parcial. Aliases de casa acumulam e o fuzzy melhora
  com mais dados, mas não há um mecanismo explícito de "mesclar dois produtos
  que a comunidade tratou como iguais". Órfãos (UNMATCHED) não têm loop de
  resolução coletiva ainda.
- **Marca:** ⚠️ parcial. Não há decisão de marca dirigida por consenso; a marca
  é setada na criação do produto e só o backfill/derivação a preenche depois.

---

## Backlog derivado deste documento

- [ ] Medir taxa de UNMATCHED (endpoint admin / métrica)
- [ ] Rodar extração de marca sobre todos os produtos + medir eficácia
- [ ] Decidir override-vs-canônico para consenso (item 2 acima)
- [ ] Opção D: notificar mudança de categoria em item já comprado
- [ ] Definir a experiência da tela de confirmação (com a FE)
