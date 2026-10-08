const express = require('express');
const app = express();
const PORT = 7000;
app.get('/health', (req, res) => {
  res.status(200).send('OK');
});
app.get('/api/products/:id',(req,res)=>{
    const traceId = req.header('X-Trace-Id');
    console.log(`[backend-a] trace=${traceId} product=${req.params.id}`);
    res.json({
        productID : req.params.id,
        name: "Sample Product",
        serveredBy: "backend-a",
        traceId
        
    });
});

app.listen(PORT,()=>{
    console.log(`backend-a is listening on port ${PORT}`);
});