const pptxgen = require("pptxgenjs");
const { applyTheme } = require("/home/renato/.claude/skills/synced/59806293-7059-4382-8236-650c390ef432_28908241-131b-40db-8cf4-69bec913cc15/pptx/scripts/apply_theme.js");

const OUT = process.argv[2] || "orbita-crm-board.pptx";

// Paleta: azul-noite dominante (a "órbita"), âmbar como acento único, verde e vermelho só com sentido de ok/problema.
const HEX = {
  night: "14213D", white: "FFFFFF", slate: "4A5568", mist: "EEF1F6",
  amber: "F5A524", teal: "2A9D8F", red: "C8414F", steel: "3D5A80", sky: "98C1D9", grey: "7A869A",
};
const THEME = {
  name: "Orbita Board",
  headFontFace: "Cambria",
  bodyFontFace: "Calibri",
  colors: {
    dk1: HEX.night, lt1: HEX.white, dk2: HEX.slate, lt2: HEX.mist,
    accent1: HEX.amber, accent2: HEX.teal, accent3: HEX.red, accent4: HEX.steel,
    accent5: HEX.sky, accent6: HEX.grey, hlink: HEX.steel, folHlink: HEX.grey,
  },
};

const pres = new pptxgen();
pres.layout = "LAYOUT_WIDE"; // 13.33 x 7.5
pres.author = "Renato Garcia";
pres.title = "Órbita CRM: plano para o board";
pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
const C = pres.SchemeColor;
const W = 13.333;

// ---------- Layouts ----------
pres.defineSlideMaster({
  title: "DARK_TITLE",
  background: { color: C.text1 },
  objects: [
    { placeholder: { options: { name: "title", type: "title", x: 0.8, y: 2.2, w: 11.7, h: 1.8, fontSize: 44, bold: true, color: C.background1, valign: "bottom", align: "left", margin: 0 }, text: "" } },
    { placeholder: { options: { name: "body", type: "body", x: 0.8, y: 4.25, w: 11.7, h: 1.2, fontSize: 20, color: C.accent5, valign: "top", align: "left", margin: 0 }, text: "" } },
  ],
});
pres.defineSlideMaster({
  title: "SECTION",
  background: { color: C.text1 },
  objects: [
    { placeholder: { options: { name: "title", type: "title", x: 2.3, y: 2.6, w: 10.2, h: 1.2, fontSize: 40, bold: true, color: C.background1, valign: "bottom", align: "left", margin: 0 }, text: "" } },
    { placeholder: { options: { name: "body", type: "body", x: 2.3, y: 3.95, w: 10.2, h: 1.0, fontSize: 20, color: C.accent5, valign: "top", align: "left", margin: 0 }, text: "" } },
  ],
  slideNumber: { x: 12.3, y: 6.95, w: 0.6, h: 0.3, fontSize: 10, color: C.accent6, align: "right" },
});
pres.defineSlideMaster({
  title: "CONTENT",
  background: { color: C.background1 },
  objects: [
    { placeholder: { options: { name: "title", type: "title", x: 0.6, y: 0.35, w: 12.1, h: 0.95, fontSize: 36, bold: true, color: C.text1, valign: "middle", align: "left", margin: 0 }, text: "" } },
    { text: { text: "Órbita CRM · plano para o board", options: { x: 0.6, y: 6.95, w: 6, h: 0.3, fontSize: 10, color: C.accent6, margin: 0 } } },
  ],
  slideNumber: { x: 12.1, y: 6.95, w: 0.6, h: 0.3, fontSize: 10, color: C.accent6, align: "right" },
});

// ---------- Helpers ----------
let objCount = 0;
const nm = (s) => `${s}-${++objCount}`;

function box(slide, x, y, w, h, fill, opts = {}) {
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x, y, w, h, rectRadius: 0.08, fill: { color: fill }, line: { color: fill, width: 0 }, objectName: nm("box"), ...opts,
  });
}
function text(slide, t, x, y, w, h, opts = {}) {
  slide.addText(t, { x, y, w, h, isTextBox: true, margin: 0, fontSize: 15, color: C.text1, valign: "top", objectName: nm("txt"), ...opts });
}
// Motivo visual: círculo numerado (a "órbita"), sempre âmbar com número escuro.
function dot(slide, label, x, y, d = 0.55, fill = C.accent1, color = C.text1) {
  slide.addShape(pres.shapes.OVAL, { x, y, w: d, h: d, fill: { color: fill }, line: { color: fill, width: 0 }, objectName: nm("dot") });
  slide.addText(String(label), { x, y, w: d, h: d, isTextBox: true, margin: 0, align: "center", valign: "middle", fontSize: d > 0.5 ? 18 : 14, bold: true, color, objectName: nm("dotnum") });
}
function arrow(slide, x1, y1, x2, y2) {
  slide.addShape(pres.shapes.LINE, { x: x1, y: y1, w: x2 - x1, h: y2 - y1, line: { color: C.accent6, width: 2, endArrowType: "triangle" }, objectName: nm("arrow") });
}
function content(title, section) {
  const s = pres.addSlide({ masterName: "CONTENT", sectionTitle: section });
  s.addText(title, { placeholder: "title" });
  return s;
}
function sectionSlide(num, title, sub, section) {
  pres.addSection({ title: section });
  const s = pres.addSlide({ masterName: "SECTION", sectionTitle: section });
  s.addShape(pres.shapes.OVAL, { x: 0.8, y: 2.75, w: 1.15, h: 1.15, fill: { color: C.accent1 }, line: { color: C.accent1, width: 0 }, objectName: nm("secdot") });
  s.addText(String(num), { x: 0.8, y: 2.75, w: 1.15, h: 1.15, isTextBox: true, margin: 0, align: "center", valign: "middle", fontSize: 36, bold: true, color: C.text1, fontFace: THEME.headFontFace, objectName: nm("secnum") });
  s.addText(title, { placeholder: "title" });
  s.addText(sub, { placeholder: "body" });
  return s;
}

// ================= ABERTURA =================
pres.addSection({ title: "Abertura" });
{
  const s = pres.addSlide({ masterName: "DARK_TITLE", sectionTitle: "Abertura" });
  s.addText("Órbita CRM: salvar o lançamento sem quebrar o produto", { placeholder: "title" });
  s.addText("Plano para o board · Renato Garcia, Tech Lead · dia 10", { placeholder: "body" });
  s.addNotes("Abrir direto: estou há 10 dias, vou dizer com franqueza onde estamos e o que fazer. São 5 blocos: diagnóstico, estratégia, código, dinheiro e as decisões que preciso de vocês hoje.");
}
{
  const s = content("A tese em uma frase", "Abertura");
  box(s, 0.6, 1.6, 12.1, 2.3, C.text1);
  text(s, "O evento é o que dá dinheiro hoje. Primeiro garanto o evento; vender o CRM ao público vem depois, em ondas.", 1.0, 1.85, 11.3, 1.8, { fontSize: 28, bold: true, color: C.background1, fontFace: THEME.headFontFace, valign: "middle" });
  const stats = [
    ["R$ 6,3 mi", "vendas da Semana do Closer que passam pelo WhatsApp dentro do CRM (70% de R$ 9 mi)"],
    ["45 dias", "até a data que o CEO anunciou em público"],
    ["60 vendedores", "e 15 mil inscritos operando ao vivo no CRM"],
  ];
  stats.forEach(([big, small], i) => {
    const x = 0.6 + i * 4.1;
    box(s, x, 4.3, 3.9, 2.3, C.background2);
    text(s, big, x + 0.3, 4.5, 3.4, 0.9, { fontSize: 36, bold: true, color: C.accent4, fontFace: THEME.headFontFace });
    text(s, small, x + 0.3, 5.45, 3.4, 1.0, { fontSize: 15, color: C.text2 });
  });
  s.addNotes("Esta é a frase que quero que fique. Para o CEO: a data se cumpre. Para o comercial: o evento é a prioridade. Para o financeiro: o retorno mais certo é proteger R$ 6,3 milhões.");
}

// ================= 1. DIAGNÓSTICO =================
sectionSlide(1, "Diagnóstico", "O que está acontecendo de verdade e o que eu ainda não sei", "1. Diagnóstico");
{
  const s = content("Onde estamos: atrasado, acima do orçamento e frágil", "1. Diagnóstico");
  const tiles = [
    ["R$ 665 mil", "gastos numa versão 1 orçada em R$ 570 mil", C.accent3],
    ["40%", "do escopo pendente: 25 de 62 funcionalidades", C.accent3],
    ["12%", "de cobertura de testes, sem teste de carga", C.accent3],
    ["25 msg/s", "pico do evento. Hoje é menos de 1 por segundo", C.accent4],
  ];
  tiles.forEach(([big, small, col], i) => {
    const x = 0.6 + i * 3.05;
    box(s, x, 1.6, 2.85, 2.4, C.background2);
    text(s, big, x + 0.25, 1.8, 2.4, 0.9, { fontSize: 34, bold: true, color: col, fontFace: THEME.headFontFace });
    text(s, small, x + 0.25, 2.75, 2.4, 1.1, { fontSize: 15, color: C.text2 });
  });
  box(s, 0.6, 4.35, 12.1, 2.2, C.text1);
  text(s, "A causa por trás de quase tudo", 1.0, 4.6, 11.3, 0.5, { fontSize: 18, bold: true, color: C.accent1 });
  text(s, "Ninguém na Órbita era dono técnico do produto. O fornecedor fazia, ninguém revisava, e as prioridades chegavam por WhatsApp de vários diretores. Agora esse dono sou eu.", 1.0, 5.15, 11.3, 1.2, { fontSize: 20, color: C.background1 });
  s.addNotes("Mostrar sem culpar ninguém, principalmente o COO. A causa é estrutural: faltava um dono técnico. 25 mensagens por segundo é pouco para um sistema bem feito; o problema é o desenho, não o volume.");
}
{
  const s = content("Seis problemas, cinco causas", "1. Diagnóstico");
  const rows = [
    ["Duplicidade e mensagem perdida (1, 2)", "Trabalho feito enquanto a Meta espera; fila no Redis sem confirmação; uma fila só para todos os clientes"],
    ["Custo de IA 4 vezes o previsto (3)", "Nenhum limite por cliente: um piloto gastou 22% do total"],
    ["Fornecedor imprevisível (4)", "Contrato por hora: a Órbita paga o retrabalho; código entra sem revisão"],
    ["Telas refeitas (5)", "O estúdio só vê a tela pronta na homologação, quando já custou caro"],
    ["Onze urgentes ao mesmo tempo (6)", "Não existe lista única nem dono da prioridade"],
  ];
  rows.forEach(([p, c], i) => {
    const y = 1.6 + i * 1.0;
    dot(s, i + 1, 0.6, y + 0.12);
    box(s, 1.35, y, 4.3, 0.8, C.background2);
    text(s, p, 1.55, y, 4.0, 0.8, { fontSize: 15, bold: true, valign: "middle" });
    arrow(s, 5.75, y + 0.4, 6.25, y + 0.4);
    text(s, c, 6.4, y, 6.3, 0.8, { fontSize: 15, color: C.text2, valign: "middle" });
  });
  s.addNotes("Os seis problemas viram cinco causas, e todas apontam para a falta de dono técnico. Cada causa tem uma solução no plano, que vou mostrar a seguir.");
}
{
  const s = content("O que ainda não sei e como descubro na semana 1", "1. Diagnóstico");
  const items = [
    ["O que quebra com carga", "Teste de carga no sistema atual, com o dobro do pico"],
    ["Estado do módulo de automações", "Leitura do código com a Ponte; só 2 devs que saíram conheciam"],
    ["De quem são o código e as contas", "Conferir repositório, AWS e conta da Meta no nome da Órbita"],
    ["O que o contrato da Ponte permite", "Prazo de aviso, multa e quem é dono do código"],
    ["Como os vendedores atendiam antes", "Esse jeito antigo é o plano B do evento"],
    ["Custo real de IA por conversa", "Medir por cliente e por dia antes de fixar o teto"],
  ];
  items.forEach(([q, how], i) => {
    const col = i % 2, row = Math.floor(i / 2);
    const x = 0.6 + col * 6.15, y = 1.6 + row * 1.7;
    box(s, x, y, 5.95, 1.5, C.background2);
    dot(s, "?", x + 0.25, y + 0.25, 0.5, C.accent4, C.background1);
    text(s, q, x + 0.95, y + 0.22, 4.8, 0.5, { fontSize: 17, bold: true });
    text(s, how, x + 0.95, y + 0.72, 4.8, 0.7, { fontSize: 15, color: C.text2 });
  });
  s.addNotes("O conselheiro de tecnologia vai gostar de ver o que eu não sei. Tudo isso se descobre em uma semana, e o plano B depende do último item: saber como os vendedores atendiam antes.");
}

// ================= 2. ESTRATÉGIA =================
sectionSlide(2, "Estratégia", "O que entra nos 45 dias, como garantir o evento e o rumo até 180 dias", "2. Estratégia");
{
  const s = content("Os 45 dias: só o que o evento precisa", "2. Estratégia");
  box(s, 0.6, 1.6, 5.95, 4.9, C.background2);
  dot(s, "✓", 0.9, 1.85, 0.55, C.accent2, C.background1);
  text(s, "Entra no lançamento", 1.6, 1.9, 4.8, 0.5, { fontSize: 20, bold: true });
  text(s, [
    { text: "Inbox e distribuição das conversas entre os 60 vendedores", options: { bullet: true, breakLine: true } },
    { text: "Histórico confiável: sem duplicar e sem perder (a PoC)", options: { bullet: true, breakLine: true } },
    { text: "Limite de envio por cliente", options: { bullet: true, breakLine: true } },
    { text: "Funil básico", options: { bullet: true, breakLine: true } },
    { text: "IA com teto de gasto e botão para desligar", options: { bullet: true } },
  ], 0.9, 2.7, 5.4, 3.6, { fontSize: 16, paraSpaceAfter: 10 });
  box(s, 6.75, 1.6, 5.95, 4.9, C.background2);
  dot(s, "×", 7.05, 1.85, 0.55, C.accent3, C.background1);
  text(s, "Sai, ou fica para depois", 7.75, 1.9, 4.8, 0.5, { fontSize: 20, bold: true });
  text(s, [
    { text: "A maior parte das 25 funcionalidades pendentes", options: { bullet: true, breakLine: true } },
    { text: "Automações congeladas para clientes de fora", options: { bullet: true, breakLine: true } },
    { text: "\"Aberto a todos\" de uma vez: vira lista de espera com entrada em ondas, por exemplo 50 clientes por dia", options: { bullet: true } },
  ], 7.05, 2.7, 5.4, 3.6, { fontSize: 16, paraSpaceAfter: 10 });
  s.addNotes("Aqui está a escolha difícil. A data do CEO se cumpre: o CRM abre no evento. O que muda é como a porta abre, em ondas, para não colocar os 60 vendedores e os R$ 6,3 milhões em risco.");
}
{
  const s = content("Linha do tempo com um ponto de decisão", "2. Estratégia");
  const steps = [
    ["Semana 1", "Descobrir o que não sei", C.accent4],
    ["Dias 1 a 35", "Arrumar só o que o evento precisa", C.accent4],
    ["10 dias antes", "Teste de carga: 50 msg/s, sem perder nem duplicar", C.accent1],
    ["Evento", "60 vendedores no CRM; público entra em ondas", C.accent2],
    ["90 a 180 dias", "Trazer o produto para dentro da Órbita", C.accent4],
  ];
  s.addShape(pres.shapes.LINE, { x: 0.9, y: 2.35, w: 11.5, h: 0, line: { color: C.accent6, width: 2 }, objectName: nm("tl") });
  steps.forEach(([when, what, col], i) => {
    const x = 0.6 + i * 2.45;
    s.addShape(pres.shapes.OVAL, { x: x + 0.85, y: 2.1, w: 0.5, h: 0.5, fill: { color: col }, line: { color: C.background1, width: 3 }, objectName: nm("tldot") });
    text(s, when, x, 1.55, 2.2, 0.45, { fontSize: 16, bold: true, align: "center", color: C.text2 });
    text(s, what, x, 2.8, 2.2, 1.1, { fontSize: 15, align: "center" });
  });
  box(s, 0.6, 4.3, 5.95, 2.25, C.background2);
  text(s, "Passou no teste", 0.95, 4.5, 5.3, 0.5, { fontSize: 18, bold: true, color: C.accent2 });
  text(s, "O evento roda no CRM, com as chaves para desligar IA e campanhas prontas se algo apertar.", 0.95, 5.05, 5.3, 1.3, { fontSize: 16 });
  box(s, 6.75, 4.3, 5.95, 2.25, C.background2);
  text(s, "Não passou: plano B", 7.1, 4.5, 5.3, 0.5, { fontSize: 18, bold: true, color: C.accent3 });
  text(s, "O evento roda do jeito antigo; o CRM segue com os 40 pilotos. A decisão é por um número, não por opinião.", 7.1, 5.05, 5.3, 1.3, { fontSize: 16 });
  s.addNotes("Para o diretor comercial: o sistema não cai ao vivo, porque 10 dias antes um teste objetivo decide. 50 mensagens por segundo é o dobro do pico estimado. Se falhar, o plano B já está pronto desde a semana 1.");
}
{
  const s = content("De 90 a 180 dias: fornecedor, UX e time", "2. Estratégia");
  const cols = [
    ["Fornecedor", [
      "Não trocar antes do evento: troca custa 1 a 2 meses pagando dois times",
      "Renegociar já: escopo fechado, revisão obrigatória, código e contas da Órbita",
      "Reduzir a Ponte de 6 para 3 devs a partir do mês 4",
    ]],
    ["UX e código", [
      "Uma biblioteca de componentes única",
      "O designer aprova a tela antes do código entrar",
      "Meta: retrabalho de tela caindo mês a mês",
    ]],
    ["Time da Órbita", [
      "Tech Lead (eu)",
      "1 SRE e 1 QA de automação já no mês 1",
      "2 devs seniores entre os meses 2 e 3",
    ]],
  ];
  cols.forEach(([h, items], i) => {
    const x = 0.6 + i * 4.1;
    box(s, x, 1.6, 3.9, 4.95, C.background2);
    dot(s, i + 1, x + 0.3, 1.85);
    text(s, h, x + 1.0, 1.9, 2.7, 0.5, { fontSize: 20, bold: true });
    text(s, items.map((t, j) => ({ text: t, options: { bullet: true, breakLine: j < items.length - 1 } })), x + 0.3, 2.7, 3.35, 3.7, { fontSize: 15, paraSpaceAfter: 10 });
  });
  s.addNotes("Para o conselheiro de tecnologia: o risco de depender de um fornecedor diminui aos poucos, com o conhecimento passando para dentro. Trocar agora seria o maior risco possível para o evento.");
}

// ================= 3. EXECUÇÃO E CÓDIGO =================
sectionSlide(3, "Execução e código", "O que eu mesmo construí, como provo e como acompanho", "3. Execução e código");
{
  const s = content("Como a PoC resolve duplicidade e perda", "3. Execução e código");
  const flow = [
    ["Meta envia ou reenvia", "o mesmo evento"],
    ["Webhook", "confere a assinatura, grava e responde 200 em poucos ms"],
    ["Fila no Postgres", "id de mensagem único: o reenvio é ignorado"],
    ["Worker", "grava o histórico; se falhar, tenta de novo em 1, 2, 4, 8 s"],
    ["Fila de falhas", "visível e reprocessável; nada some em silêncio"],
  ];
  flow.forEach(([h, d], i) => {
    const x = 0.6 + i * 2.5;
    box(s, x, 1.65, 2.2, 2.2, i === 4 ? C.text1 : C.background2);
    text(s, h, x + 0.15, 1.8, 1.9, 0.5, { fontSize: 16, bold: true, color: i === 4 ? C.accent1 : C.text1 });
    text(s, d, x + 0.15, 2.35, 1.9, 1.4, { fontSize: 14, color: i === 4 ? C.background1 : C.text2 });
    if (i < 4) arrow(s, x + 2.22, 2.75, x + 2.48, 2.75);
  });
  box(s, 0.6, 4.2, 12.1, 2.35, C.background2);
  text(s, "Na saída", 0.95, 4.4, 4, 0.5, { fontSize: 18, bold: true });
  text(s, [
    { text: "A resposta ou campanha é gravada antes de enviar: se cair no meio, ela continua lá", options: { bullet: true, breakLine: true } },
    { text: "Cada cliente tem um limite de envios por segundo, compartilhado entre todos os servidores", options: { bullet: true, breakLine: true } },
    { text: "Resposta automática também tem chave única: nunca sai duas vezes", options: { bullet: true } },
  ], 0.95, 4.95, 11.4, 1.5, { fontSize: 15, paraSpaceAfter: 6 });
  s.addNotes("Ponto que o conselheiro de tecnologia vai perguntar: a PoC é em Kotlin, o produto é Node. O mecanismo está no banco: UNIQUE com ON CONFLICT para o id único, SKIP LOCKED para os workers não pegarem o mesmo item, FOR NO KEY UPDATE para o limite por cliente. Isso vale igual em Node; levar para o serviço atual custa uns 2 a 3 dias. Não proponho reescrever o sistema.");
}
{
  const s = content("Demo de 2 minutos: quatro provas", "3. Execução e código");
  const proofs = [
    ["50 requisições iguais ao mesmo tempo", "1 registro e 1 resposta"],
    ["Derrubo 1 dos 3 servidores no meio", "nada se perde"],
    ["Campanha de 1.000 do cliente A", "não atrasa o cliente B"],
    ["Relatório de conferência", "zero mensagens sem registro"],
  ];
  proofs.forEach(([a, b], i) => {
    const col = i % 2, row = Math.floor(i / 2);
    const x = 0.6 + col * 6.15, y = 1.6 + row * 2.0;
    box(s, x, y, 5.95, 1.8, C.background2);
    dot(s, i + 1, x + 0.3, y + 0.3);
    text(s, a, x + 1.05, y + 0.3, 4.7, 0.6, { fontSize: 17, bold: true });
    text(s, b, x + 1.05, y + 0.95, 4.7, 0.6, { fontSize: 22, bold: true, color: C.accent2, fontFace: THEME.headFontFace });
  });
  text(s, "Medido no notebook: 500 msg/s com p99 de 2 ms no webhook. O pico do evento é 25 msg/s.", 0.6, 5.75, 12.1, 0.5, { fontSize: 15, color: C.text2 });
  s.addNotes("Rodar o scripts/demo.sh ou o Run all do orbita.http. Se a demo ao vivo falhar, ter um vídeo gravado de reserva. Mostrar a reconciliação no fim: é o número que o COO pode cobrar.");
}
{
  const s = content("Rituais e as métricas que o COO consegue cobrar", "3. Execução e código");
  const hdr = { bold: true, color: C.background1, fill: { color: C.text1 }, fontSize: 15 };
  const rows = [
    [{ text: "Métrica", options: hdr }, { text: "Meta", options: hdr }, { text: "Por quê", options: hdr }],
    ["Mensagens duplicadas", "0%", "Hoje é 3%"],
    ["Mensagens sem registro", "0", "Hoje é 1,8%, e ninguém sabe quais"],
    ["Resposta do webhook (p99)", "abaixo de 200 ms", "A Meta reenvia quando demora"],
    ["Custo de IA por cliente por dia", "dentro do teto", "Um piloto gastou 22% do total"],
    ["Entrega por sprint", "variação menor que 20%", "Hoje oscila de 14 a 31 pontos"],
  ];
  s.addTable(rows, { x: 0.6, y: 1.6, w: 7.6, colW: [3.0, 2.0, 2.6], fontSize: 15, color: C.text1, border: { type: "solid", pt: 0.75, color: "D9E0E8" }, rowH: 0.62, valign: "middle" });
  box(s, 8.5, 1.6, 4.2, 4.95, C.background2);
  text(s, "Rituais", 8.8, 1.85, 3.6, 0.5, { fontSize: 20, bold: true });
  text(s, [
    { text: "Daily de 15 min com a Ponte", options: { bullet: true, breakLine: true } },
    { text: "Revisão obrigatória antes de todo merge", options: { bullet: true, breakLine: true } },
    { text: "Relatório semanal ao COO com estes 5 números", options: { bullet: true, breakLine: true } },
    { text: "Reunião semanal de prioridade, com o COO como dono", options: { bullet: true, breakLine: true } },
    { text: "Decisão 10 dias antes do evento", options: { bullet: true } },
  ], 8.8, 2.5, 3.65, 3.9, { fontSize: 15, paraSpaceAfter: 10 });
  s.addNotes("Para o COO: previsibilidade e linguagem simples. Ele recebe cinco números toda semana e consegue cobrar. Ele deixa de ser o culpado do atraso e passa a ser o dono da prioridade.");
}

// ================= 4. DINHEIRO =================
sectionSlide(4, "Custos e retorno", "Quanto custa, quando se paga e quanto custa errar", "4. Custos e retorno");
{
  const s = content("O plano cabe no R$ 1,2 mi, com reserva", "4. Custos e retorno");
  const months = ["M1", "M2", "M3", "M4", "M5", "M6"];
  const data = [
    { name: "Ponte", labels: months, values: [95, 95, 95, 70, 50, 50] },
    { name: "UX", labels: months, values: [15, 15, 15, 15, 15, 15] },
    { name: "AWS", labels: months, values: [16, 20, 16, 16, 16, 16] },
    { name: "IA", labels: months, values: [15, 15, 12, 12, 12, 12] },
    { name: "Contratações", labels: months, values: [33, 55, 77, 77, 77, 77] },
  ];
  s.addChart(pres.charts.BAR, data, {
    x: 0.6, y: 1.5, w: 7.9, h: 5.2, barDir: "col", barGrouping: "stacked", objectName: "chart-orcamento",
    chartColors: [HEX.steel, HEX.sky, HEX.grey, HEX.amber, HEX.teal],
    showLegend: true, legendPos: "b", legendFontSize: 12, legendColor: HEX.slate, legendFontFace: "+mn-lt",
    showTitle: true, title: "Gasto por mês, em R$ mil", titleFontSize: 14, titleColor: HEX.slate, titleFontFace: "+mn-lt",
    catAxisLabelColor: HEX.slate, valAxisLabelColor: HEX.slate, catAxisLabelFontSize: 12, valAxisLabelFontSize: 12,
    catAxisLabelFontFace: "+mn-lt", valAxisLabelFontFace: "+mn-lt",
    valAxisMaxVal: 250, valAxisMajorUnit: 50,
    valGridLine: { color: "D9E0E8", size: 0.5 }, catGridLine: { style: "none" },
  });
  const kpis = [["R$ 1.119 mil", "total em 6 meses"], ["R$ 81 mil", "de reserva (7%)"], ["R$ 38 mil/mês", "era o que sobrava para contratar sem cortes"]];
  kpis.forEach(([b, l], i) => {
    const y = 1.6 + i * 1.65;
    box(s, 8.8, y, 3.9, 1.45, C.background2);
    text(s, b, 9.05, y + 0.15, 3.5, 0.65, { fontSize: 28, bold: true, color: C.accent4, fontFace: THEME.headFontFace });
    text(s, l, 9.05, y + 0.82, 3.5, 0.5, { fontSize: 14, color: C.text2 });
  });
  s.addNotes("Hoje se gastam R$ 162 mil por mês; o orçamento dá R$ 200 mil. Cortes: UX de 25 para 15 mil, IA de 28 para 12 a 15 mil com teto, Ponte reduzida depois do evento. O mês 3 passa de 200 porque Ponte e time novo convivem; os meses seguintes compensam.");
}
{
  const s = content("Três cenários para os próximos 12 meses", "4. Custos e retorno");
  s.addChart(pres.charts.BAR, [{ name: "Clientes no mês 12", labels: ["Pessimista", "Base", "Meta do CEO"], values: [260, 430, 800] }], {
    x: 0.6, y: 1.5, w: 6.6, h: 5.2, barDir: "col", objectName: "chart-cenarios",
    chartColors: [HEX.steel], showLegend: false,
    showTitle: true, title: "Clientes pagantes no mês 12", titleFontSize: 14, titleColor: HEX.slate, titleFontFace: "+mn-lt",
    showValue: true, dataLabelPosition: "outEnd", dataLabelColor: HEX.night, dataLabelFontSize: 14, dataLabelFontFace: "+mn-lt", dataLabelFontBold: true,
    catAxisLabelColor: HEX.slate, valAxisLabelColor: HEX.slate, catAxisLabelFontSize: 13, valAxisLabelFontSize: 12,
    catAxisLabelFontFace: "+mn-lt", valAxisLabelFontFace: "+mn-lt",
    valAxisMaxVal: 900, valAxisMajorUnit: 300,
    valGridLine: { color: "D9E0E8", size: 0.5 }, catGridLine: { style: "none" },
  });
  const hdr = { bold: true, color: C.background1, fill: { color: C.text1 }, fontSize: 14 };
  s.addTable([
    [{ text: "Cenário", options: hdr }, { text: "No evento", options: hdr }, { text: "Novos/mês", options: hdr }, { text: "Receita mês 12", options: hdr }],
    ["Pessimista", "75", "25", "R$ 129 mil"],
    ["Base", "150", "40", "R$ 214 mil"],
    ["Meta do CEO", "300", "73", "R$ 398 mil"],
  ], { x: 7.5, y: 1.6, w: 5.2, colW: [1.5, 1.1, 1.1, 1.5], fontSize: 14, color: C.text1, border: { type: "solid", pt: 0.75, color: "D9E0E8" }, rowH: 0.5, valign: "middle" });
  text(s, [
    { text: "Para chegar a 800 com 5% de cancelamento por mês, são 73 clientes novos todo mês.", options: { bold: true, breakLine: true } },
    { text: "Premissas: ticket de R$ 497; sobram uns R$ 385 por cliente depois de servidor, IA com teto e suporte; custo fixo de R$ 120 mil por mês depois dos 6 meses.", options: {} },
  ], 7.5, 3.95, 5.2, 2.6, { fontSize: 15, paraSpaceAfter: 10 });
  s.addNotes("Questionar a meta com respeito: 2% dos inscritos é otimista para um produto com 40% do escopo pendente. Defendo 1% como base. No cenário pessimista, o produto não se paga. Dizer as premissas em voz alta.");
}
{
  const s = content("Quanto custa errar", "4. Custos e retorno");
  box(s, 0.6, 1.6, 5.95, 4.95, C.text1);
  text(s, "R$ 630 mil", 1.0, 1.95, 5.2, 1.2, { fontSize: 54, bold: true, color: C.accent1, fontFace: THEME.headFontFace });
  text(s, "é o que se perde se o CRM cair no evento e levar só 10% das vendas. Metade do orçamento do semestre.", 1.0, 3.25, 5.2, 1.5, { fontSize: 20, color: C.background1 });
  text(s, "Proteger o evento é o retorno mais certo do plano.", 1.0, 5.2, 5.2, 1.0, { fontSize: 18, bold: true, color: C.accent5 });
  box(s, 6.75, 1.6, 5.95, 4.95, C.background2);
  text(s, "IA sem teto dá prejuízo", 7.1, 1.85, 5.3, 0.5, { fontSize: 20, bold: true });
  text(s, "R$ 6,2 mil", 7.1, 2.5, 2.6, 0.8, { fontSize: 32, bold: true, color: C.accent3, fontFace: THEME.headFontFace });
  text(s, "por mês de IA do piloto que mais usa", 7.1, 3.3, 2.6, 0.9, { fontSize: 14, color: C.text2 });
  text(s, "R$ 1.497", 9.9, 2.5, 2.6, 0.8, { fontSize: 32, bold: true, color: C.accent4, fontFace: THEME.headFontFace });
  text(s, "preço do plano Scale, o único com IA", 9.9, 3.3, 2.6, 0.9, { fontSize: 14, color: C.text2 });
  text(s, "Com teto por cliente, igual ao limite de envio da PoC, a IA para no teto e um vendedor assume. Vender o CRM ao público é uma aposta: no cenário base, se paga em mais de 2 anos.", 7.1, 4.4, 5.3, 2.0, { fontSize: 15 });
  s.addNotes("Para o conselheiro financeiro, que já viu projeto dobrar de custo: sou honesto que o produto para o público é aposta. O que justifica o investimento agora é proteger o evento e parar o vazamento de IA.");
}

// ================= 5. RISCOS E DECISÕES =================
sectionSlide(5, "Riscos e decisões", "O que aciona o plano B e o que preciso do board hoje", "5. Riscos e decisões");
{
  const s = content("Riscos e o que fazer se acontecerem", "5. Riscos e decisões");
  const hdr = { bold: true, color: C.background1, fill: { color: C.text1 }, fontSize: 15 };
  s.addTable([
    [{ text: "Risco", options: hdr }, { text: "Como percebo", options: hdr }, { text: "O que faço", options: hdr }],
    ["O sistema não aguenta o pico", "Teste de carga falha 10 dias antes", "Plano B: evento no jeito antigo"],
    ["A Ponte não aceita renegociar", "Resposta em até 2 semanas", "Começar a transição já, com reserva do orçamento"],
    ["Não consigo contratar o SRE a tempo", "Sem contratação até o dia 15", "SRE por consultoria pontual só para o evento"],
    ["A IA estoura o teto", "Painel de custo diário", "A IA para no teto e o vendedor assume"],
    ["Conversão abaixo de 1%", "Números da primeira semana de ondas", "Reduzir a meta e o custo fixo antes do mês 4"],
  ], { x: 0.6, y: 1.6, w: 12.1, colW: [3.8, 3.8, 4.5], fontSize: 15, color: C.text1, border: { type: "solid", pt: 0.75, color: "D9E0E8" }, rowH: 0.75, valign: "middle" });
  s.addNotes("Cada risco tem um número que o dispara e uma ação já decidida. Assim ninguém precisa decidir sob pressão na véspera do evento.");
}
{
  const s = pres.addSlide({ masterName: "SECTION", sectionTitle: "5. Riscos e decisões" });
  text(s, "O que peço ao board decidir hoje", 0.8, 0.55, 11.7, 0.9, { fontSize: 36, bold: true, color: C.background1, fontFace: THEME.headFontFace });
  const ds = [
    "Evento no CRM; \"aberto a todos\" vira lista de espera com entrada em ondas",
    "Orçamento reorganizado: SRE e QA já, e teto de gasto de IA por cliente",
    "Renegociar a Ponte agora, sem trocar antes do evento",
    "Decisão por teste de carga 10 dias antes do evento, com plano B pronto",
    "Uma lista de prioridades, um dono (o COO) e um canal só",
  ];
  ds.forEach((d, i) => {
    const y = 1.75 + i * 1.0;
    dot(s, i + 1, 0.8, y + 0.1);
    text(s, d, 1.65, y, 10.9, 0.75, { fontSize: 20, color: C.background1, valign: "middle" });
  });
  s.addNotes("Fechar pedindo as cinco decisões, uma a uma. Se houver tempo, uma frase sobre como usei IA: usei para escrever o código e conferi cada mecanismo com testes, inclusive testes que quebram o código de propósito.");
}
{
  const s = content("Como usei IA e o que verifiquei", "5. Riscos e decisões");
  box(s, 0.6, 1.6, 5.95, 3.2, C.background2);
  text(s, "Onde usei", 0.95, 1.85, 5.3, 0.5, { fontSize: 20, bold: true });
  text(s, [
    { text: "Escrever o código da PoC e os testes", options: { bullet: true, breakLine: true } },
    { text: "Revisar o desenho e procurar falhas", options: { bullet: true, breakLine: true } },
    { text: "Organizar as contas do business case", options: { bullet: true } },
  ], 0.95, 2.5, 5.3, 2.1, { fontSize: 16, paraSpaceAfter: 10 });
  box(s, 6.75, 1.6, 5.95, 3.2, C.background2);
  text(s, "O que conferi por conta própria", 7.1, 1.85, 5.3, 0.5, { fontSize: 20, bold: true });
  text(s, [
    { text: "Testes que quebram o código de propósito: sem id único, sem limite, sem trava. O teste pega cada um", options: { bullet: true, breakLine: true } },
    { text: "Derrubar servidor no meio do envio: nada se perde", options: { bullet: true, breakLine: true } },
    { text: "Cada número do orçamento e dos cenários, refeito à mão", options: { bullet: true } },
  ], 7.1, 2.5, 5.3, 2.1, { fontSize: 16, paraSpaceAfter: 10 });
  s.addNotes("O desafio pede para contar como usei IA. O ponto é mostrar que eu verifiquei: sei explicar cada mecanismo sem ajuda e mostro os testes de sabotagem.");
}

(async () => {
  await pres.writeFile({ fileName: OUT });
  await applyTheme(OUT, THEME);
  console.log("ok", OUT);
})();
